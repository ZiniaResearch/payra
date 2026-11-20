# Authentication and account profile

Google login, local access-JWT verification, signed refresh/logout, and profile
reads are implemented. Live browser/database verification remains outstanding;
see the [merge checklist](architecture.md#verification-and-merge-checklist).
Environment setup belongs in [architecture](architecture.md#local-development).

## Credentials and storage

| Location | Contents |
| --- | --- |
| Cookies | Host-only `Secure`, `HttpOnly`, `SameSite=Lax` JWTs: `payra_access` at `/api`, refresh at `/api/auth`. JavaScript cannot read them. |
| IndexedDB | `payra-auth` version 1: `browserKeys` holds CryptoKey pairs with non-exportable P-256 private keys; public keys are exportable. `authSession/current` holds `{ session_id, refresh_token_hash, keyId }`, not tokens. |
| PostgreSQL | `accounts` and one `auth_sessions` row per login, with account ID, public key, counter, and created/idle/absolute deadlines. No refresh tokens or used-proof IDs are stored. |

Access lasts **10 minutes**. Refresh inactivity is **30 days**, capped by a
**90-day absolute** session deadline. Keep separate access/refresh JWT secrets
stable across restarts. All cooperating login/refresh/logout operations use the
`payra-refresh` Web Lock on the React origin.

## Login

1. Google's authorization-code popup returns a code to the browser.
2. Under the auth lock, React generates/saves a P-256 key pair and sends
   `{ code, browser_public_key }` to `POST /api/auth/google`.
3. Axum checks Origin/request header and key, exchanges the code server-side, and
   verifies Google's ID token signature, issuer, audience, and expiry.
4. The server creates/reuses the account by Google subject, updates its email,
   creates a session at counter 1, sets JWT cookies, and returns
   `{ session_id, refresh_token_hash }`.
5. React saves that metadata with its key ID and cancels/clears prior-account
   Query/Router caches under the lock. It releases the lock before navigating to
   `/messages/new` (Schedule SMS), where the sidebar independently requests the
   profile.

The browser never receives raw Payra JWTs in JSON. Each login has its own session;
the sidebar does not use IndexedDB presence as proof of authentication.

## Profile

Google initializes the profile; users can edit their name, choose a preset cover,
upload a profile photo when storage is configured, or delete the account.
See the [profile roadmap](architecture.md#profile-implementation-todo).

`GET /api/profile` uses shared access-JWT middleware, then selects the profile from
`accounts` using only the verified account ID. It returns `Cache-Control: no-store`:

```json
{
  "account_id": "account UUID",
  "email": "person@example.com",
  "name": "Person",
  "avatar_url": "https://media.example.com/default-profile.webp",
  "cover_url": "https://media.example.com/cover-2.webp",
  "cover_id": "cover-2",
  "avatar_uploads_enabled": true
}
```

At account creation, verified optional Google name/photo claims initialize the
required `name`/`avatar_url` fields; missing values use `Account` and the shared
default avatar, and Google photo URLs must be HTTPS without embedded credentials.
`cover_url` is randomly selected from three presets. Presets and the fallback
avatar use the public Garage read URL configured by `MEDIA_BASE_URL`. Later
sign-ins update email only, preserving saved profile values, including older
image URLs. There are no separate Google-default/override fields.

No query/body parameter selects an account. No session-table read, cookie change,
session ID, or Google subject is part of this response.
The Profile modal is the UI; there is no separate `/profile` page.

| Failure | Result |
| --- | --- |
| Missing/expired access | `401 access_missing` / `401 access_expired`; attempt bounded access recovery. |
| Invalid access | `401 access_invalid`; terminal auth error. |
| Account deleted | `401 account_missing`; terminal error without refresh. |
| Database failure | `503 profile_unavailable`; ordinary retryable error. |

Errors use `{ "error": "code" }` and are also non-cacheable through middleware.
`ui/src/modules/profile/queries.ts` calls `authenticatedRequest()` directly,
forwarding the Query abort signal. It uses `["account", "current"]`, a 60-second
stale time, the global retry defaults, and no polling. Successful name, cover,
and avatar saves cancel an in-flight profile read and update only the changed
fields in the shared cache, without forcing another profile GET.

`AccountMenu.tsx` owns the query and one logout hook. It shows a skeleton, the real
name/email/avatar, or an unavailable state with retry inside its popover.
Account Details above the red Sign out button opens a profile-only modal. Its
edge-to-edge cover, overlapping avatar, and name use the same profile query.
Failed avatar loads show initials, and missing/failed cover images retain the
semantic background. Email is read-only, with a loading skeleton and an
unavailable fallback. The branding footer is hidden. Theme/logout remain usable
while data loads or fails. The shell never waits for this query; other consumers
should reuse its options and cache.

### Edit name and cover

`GET /api/profile/covers` lists `{ id, label, url }` presets without querying the
database. `PATCH /api/profile` accepts `name`, `cover_id`, or both. Names are
trimmed and must contain 1–80 Unicode code points without control characters.
The server resolves preset IDs to URLs and rejects arbitrary URLs, empty updates,
and other fields. It returns the saved profile after one account-scoped update.
An older bundled cover still maps to its recognized preset; database URLs are
not backfilled. `cover_id` is `null` if the saved URL is not recognized.
The cover pencil opens a popover beside or below the trigger, with live preview.
Cancel, Escape, and outside dismissal discard the draft; only Save persists it.

### Upload an avatar

`PUT /api/profile/avatar` accepts raw JPEG, PNG, or WebP bytes (maximum 5 MiB and
4096 × 4096 pixels). Payra checks the decoded content and stores a static WebP,
at most 512 × 512 pixels, without the original metadata. Only two uploads run
at once per API process. The handler uses the verified access JWT without a
session-table lookup. It returns the updated profile; an invalid or failed
upload does not replace the old photo.

The private S3 API uses a key scoped to the avatar bucket. Read URLs are public
to anyone with the link; uploads use unique keys and are written with `no-store`.
Payra generates the object key in Rust and uploads the new file before saving
its URL and key in a database transaction. Only after commit does it try
deleting the previous owned photo in the background, with one retry. Google
photos and shared presets are never deleted. There is no cleanup queue or
polling: a failed database write or failed removal can leave an unused object
in Garage.

### Delete account

The profile footer asks for one explicit confirmation, without a popup or
Google sign-in. The confirmed request requires an active Payra session and proof
from its registered browser key. This verifies possession of the key, not fresh
user authentication; a person with access to the unlocked, signed-in browser
can still confirm deletion.

1. After confirmation, `POST /api/profile/deletion` issues a 60-second nonce
   for the current active session, replacing any previous nonce for that session.
   The UI checks the returned account ID against the profile being deleted.
2. `DELETE /api/profile` accepts `{ "nonce": "…", "proof": "…" }`. The server
   locks the account, active session, and matching challenge. It verifies an
   ES256 proof signed by that session’s browser key, using the
   `payra-delete-profile-proof+jwt` type, `DELETE` method, exact API URL, session
   ID, timestamp, and base64url SHA-256 hash of the nonce. Refresh/logout proofs
   are not accepted. Neither request creates a login.
3. The transaction deletes the account. PostgreSQL cascades to every session
   and its challenge. After commit Payra tries to delete the current owned
   avatar in the background, with one retry. The API clears both cookies and
   returns the ended session ID. The UI reuses the existing local key/cache
   cleanup and session-ended notification.

Deletion removes the Payra profile and sessions from the live DB immediately.
An uploaded photo may remain reachable by its URL if removal fails or the API
restarts before it finishes. There is no persistent retry after restart.
Backups and copies made by others are not erased by the request. No defined
production backup retention window, billing records, or message records exist
yet. Before production, set and disclose backup expiry and ensure a restore
does not republish deleted data. This does not delete the Google Account;
signing in again creates a new Payra account.

## Protected requests and shared errors

Access middleware locally verifies JWT signature, algorithm, issuer, audience,
type, expiry, and account/session UUIDs without reading sessions. The verified
session ID is checked explicitly for account deletion. Profile reads,
name/cover edits, and avatar uploads do not read the session table. All profile
writes use the shared exact-Origin and `X-Requested-With` requirement.

`authenticatedRequest()` has three steps:

1. `requestWithAccess()` sends the original request. Only `access_missing` or
   `access_expired` permits refresh; other 401s throw `AuthenticationRequiredError`.
2. Under `payra-refresh`, recheck the same request: another tab may already have
   updated the shared cookies.
3. If still necessary, `refreshAndRetry()` signs/posts refresh, saves its metadata,
   and retries the original request once **before releasing the lock**. There is
   no recursive refresh loop.

Successful ordinary requests do not read IndexedDB. Refresh/logout use the bare
Axios client, not the protected wrapper. The default request timeout is 15
seconds; avatar upload uses 30 seconds.
An already-started rotation is not canceled by page navigation. A transient
feature Query retry starts again at the original request, not a captured proof.

`configureAuthFailureHandling()` is registered once from `router.tsx` with the
Query/Mutation cache error callbacks. For a terminal error, it takes the existing
auth lock, cancels queries, clears Query/Router caches, navigates to `/login`, and
clears the departed route cache again. Concurrent errors per QueryClient share
one in-flight cleanup promise; it resets afterward. SSR, unrelated errors, and
failures already at `/login` are ignored. Cleanup failure logs a generic diagnostic.

This handler does **not** call signed logout, delete server sessions, or
unconditionally erase IndexedDB. Refresh/request paths keep targeted cleanup:
missing key/metadata or refresh 401 requires sign-in, and a post-refresh 401 clears
the expected local session/key and emits session-ended. An initial terminal
`account_missing` can navigate without deleting local metadata.

| Query failure | Default automatic retry |
| --- | --- |
| Terminal auth or cancellation | None. |
| Axios network error or HTTP 5xx | At most two retries. |
| Other errors, including HTTP 403 and non-Axios storage errors | None; use local/manual recovery. |
| Mutations | None. |

Offline work may pause with a pending skeleton; that is not an auth failure.
Network/storage/403/server failures from refresh do not force sign-out or erase
keys. Bare login/refresh/logout calls have no transport-level automatic retry.
Feature requests must use Query/Mutation APIs to invoke global cache callbacks;
the HTTP wrapper alone does not navigate.

There is no request-session tracker, per-login generation, additional cancellation
controller, or session-started notification. Cache cancellation is not
stale-response isolation across logins and cannot undo accepted server mutations.

## Browser proofs

Refresh/logout POSTs require the configured Origin and
`X-Requested-With: XmlHttpRequest`, plus `{ "proof": "compact ES256 JWS" }`.
The signed header uses `alg: ES256` and purpose-specific `typ`:

| Operation | URL path | `typ` |
| --- | --- | --- |
| Refresh | `/api/auth/refresh` | `payra-refresh-proof+jwt` |
| Logout | `/api/auth/logout` | `payra-logout-proof+jwt` |

Illustrative refresh payload:

```json
{
  "htm": "POST",
  "htu": "http://localhost:8000/api/auth/refresh",
  "iat": 1794700000,
  "sid": "the-session-uuid",
  "ath": "base64url-sha256-of-the-exact-refresh-jwt"
}
```

The browser generates current Unix time. `ath` is unpadded
`Base64url(SHA-256(refresh JWT))`; JavaScript receives its fingerprint, not the
cookie token. Web Crypto signs P-256/SHA-256 with raw `r || s` ES256 encoding.
Proofs may be at most 60 seconds old or 5 seconds in the future, with a 2048-byte
proof limit and 4096-byte refresh/logout JSON limit.

The backend uses only the session's saved public key and configured public
`API_ORIGIN` URLs—not Host/forwarding headers or a key supplied in the proof.
Purpose and URL separate refresh/logout even with the same key/hash. Production
requires public HTTPS origins; frontend `VITE_API_URL` must match `API_ORIGIN`.

## Refresh

The refresh JWT carries session ID, counter, and expiry with separate signing
key/audience/type from access. The backend locks its session with `FOR UPDATE`,
checks deadlines after locking, verifies the token-matching browser proof, then
compares counters:

| Case | Behavior |
| --- | --- |
| Matching counter | Increment once, extend inactivity up to the unchanged absolute deadline, prepare cookies/hash, commit, then return 200. |
| Lower counter with a valid recent matching proof | Delete session, commit, return `401 refresh_reused`; exact duplicates are included. |
| Bad key/proof, mismatched hash, stale proof, or future counter | Reject without rotation/revocation. |
| Missing/expired session or refresh credentials | Reject; browser requires sign-in. |
| Database/network/storage failure | Ordinary failure; preserve credentials for recovery. |

There is no proof-use table or replay grace. Atomic rotation prevents one
generation from succeeding twice. Old proof plus newer cookie tests hash mismatch,
not old-token reuse.

If a rotation commits but its response is lost, another attempt with the old
cookie and a fresh matching proof revokes the login. If cookies update but local
metadata does not, the next proof fails. Re-login is the recovery. A stolen token
and matching recent proof can win one race; later revocation cannot undo actions
already performed.

## Logout and tab synchronization

1. Open the account popover and click **Sign out**. Under `payra-refresh`, reload
   the latest session/hash/key and sign a fresh logout-purpose proof.
2. POST `/api/auth/logout` with the refresh cookie; no access token is required.
   Axum checks Origin/JWT/proof, locks/deletes the session, and **commits before
   clearing cookies**. Logout does not rotate tokens or increment counters.
3. On 204 with `no-store` and both exact-scope cookie removals, delete only the
   expected IndexedDB session/key, broadcast session-ended, cancel/clear
   Query/Router caches, navigate to `/login`, then release the lock.

| Case | Behavior |
| --- | --- |
| Invalid Origin/header | `403 logout_forbidden`; no changes. |
| Invalid refresh JWT | `401 logout_invalid`; no changes. |
| Invalid signature/purpose/URL/time/session/hash | `401 logout_proof_invalid`; no changes to a live session/cookies. |
| Future counter | `401 logout_counter_invalid`; no changes. |
| Valid older refresh JWT + matching fresh logout proof | May delete that login; logout is not rotation. |
| Database failure | `503 logout_unavailable`; rollback and preserve local credentials. |
| Network/storage failure | No false-success navigation or automatic retry; manual retry can finish a committed logout. |
| Already-deleted session with valid cookie | Idempotent 204 and cookie removal. |
| Missing/expired refresh cookie | 204 clears cookies only; no server row is identified/deleted. |
| Missing local key/metadata | Client sends empty proof only to check already-ended cases. A live session rejects it; show verification error and reauthenticate. |

One mounted `useLogout()` uses private `useLogoutSync()` to subscribe to the
`payra-auth` BroadcastChannel in an effect and close it on unmount. It is
event-driven, not polling. Notices contain only `{ type: "session-ended",
session_id }`, never keys/proofs/tokens/hashes, and are not authentication authority.
Definitive refresh rejection also emits them; transient errors do not.

Receivers take the auth lock and read current metadata. Matching/absent current
sessions leave the protected view; a different newer login instead clears the old
view/revalidates routes outside the lock. This protects against delayed notices,
not arbitrary stale HTTP responses. Without BroadcastChannel, other tabs discover
missing cookies on their next API request.

## Manual verification

All live checks below remain outstanding. Use disposable development logins;
keep DevTools Network logs, but never publish copied credentials/proofs.

### Login, profile, and errors

- Sign in; `/messages/new` renders immediately with Schedule SMS selected. The
  account skeleton becomes the correct name/email/avatar. Profile returns the
  correct UUID/email/name/avatar URL/cover URL with `no-store`, no JWT, session
  ID, or Google subject. A query parameter naming another account must
  not change the result; a second Google account must return its own profile.
- Check that a new account receives one of the three media-hosted covers and
  that the same saved cover survives reloads and later sign-ins. Check missing Google
  claims, preset asset URLs, failed-avatar initials, cover-background fallback,
  and consistent sidebar/modal data.
- Remove access and refresh cookies, then reload: shared cleanup/login navigation,
  without a loop or old cached identity. The empty shell may appear briefly.
- With a disposable account and valid access cookie, delete its account row:
  `401 account_missing` should invoke terminal handling without refresh.
- Stop PostgreSQL/API or go offline: expect bounded retry or paused work, not
  forced logout/erased credentials. Restore connectivity; resume or use **Try
  again** in the account popover. Theme/logout must not wait for profile data.
- When more queries/mutations exist, verify concurrent terminal failures share
  cleanup per QueryClient and mutations are not automatically replayed.

### Profile edits, uploads, and deletion

- Change a name and a preset cover independently. A cover draft should preview
  immediately. Clicking outside or choosing Cancel should restore the saved
  cover. Save a different preset, then reload and sign in again to verify
  persistence. A successful save must not force another profile GET, and
  invalid preset IDs must leave the saved cover unchanged.
- With a dedicated avatar bucket configured, upload a JPEG/PNG/WebP and verify
  both modal and sidebar update. Try a non-image, oversized image, and an
  unavailable Garage server; none should replace the saved photo. Replace the
  photo with Garage healthy, check the old URL stops serving, and verify preset
  URLs still work. A failed old-file delete must not fail the saved profile change.
- Use a disposable account. Cancel inline confirmation; nothing should be sent.
  A missing browser key, invalid proof, replaced/expired challenge, or revoked
  Payra session must not delete the account. Confirm normally: no Google prompt,
  all session rows disappear, the other tab signs out, and signing in again
  creates a new account ID. With Garage healthy, the uploaded URL should stop
  serving. If removal fails, the account should still be deleted.

### Refresh

- With valid access, successful profile reads must not trigger refresh.
- Delete only `payra_access` (or wait 10 minutes), then reload. Expect profile
  401 → lock/recheck → one refresh 200/two cookies → final profile 200, no login.
  The local session/key IDs stay unchanged, the hash changes, and the database
  counter increases once; inactivity advances but never beyond absolute expiry.
- Repeat with two tabs reloading nearly together: one rotation, one counter
  increment, both account views succeed.
- In separate logins, remove the private key/refresh cookie or expire the idle
  deadline, then remove access/reload: sign-in, no issuance or refresh loop.
- Change only the stored hash, then expire access: `401 proof_invalid`, unchanged
  database row/counter, targeted local cleanup, and login navigation.
- Using local cURL, try wrong Origin (403), signatures, URLs, time, or hash:
  reject without rotating/deleting the row. Re-sign altered payloads when testing
  bindings independently; editing a signed payload also invalidates its signature.
- Replay an accepted refresh with its **original cookie/proof** within 60 seconds:
  `401 refresh_reused` and row deletion. After the proof window, reject without
  stale-counter revocation alone. Browser fetch cannot set Cookie manually.

Inspect development sessions without copying tokens:

```sql
SELECT id, refresh_counter, idle_expires_at, absolute_expires_at
FROM auth_sessions
ORDER BY created_at DESC
LIMIT 5;
```

### Logout

- Note the session ID from login JSON or IndexedDB, not the empty analytics page.
  Sign out: 204, `no-store`, two cookie removals, no rotation/refresh; database row
  and expected local session/key disappear, unrelated entries remain, URL is login.
- Delete access and sign out **without reloading**: still succeeds via refresh
  cookie/proof. Reloading first tests refresh instead.
- Sign out in one of two tabs: both leave the protected view. Also race a tab-A
  refresh with tab-B logout: B waits and signs using the newly saved hash.
- Use Back/direct `/analytics`, then sign in as another account: no old identity
  should return; a delayed old-session notice must not sign out the newer login.
- Send a refresh-purpose proof to logout, or wrong signature/URL/hash/time:
  401, live row/cookies unchanged. Wrong Origin gives 403.
- Remove the local key while keeping a live refresh cookie: verification error,
  no claimed revocation, row remains. Separately test offline logout: no
  navigation, retain credentials for retry, restore connectivity and retry.
- Replay a completed logout or retry after a lost committed response: idempotent
  cleanup, no resurrection. Missing/expired refresh clears cookies without claiming
  that an unidentified row was deleted.

## Code map and limitations

- Backend: `server/src/modules/auth/` owns Google exchange, tokens, cookies,
  middleware, proofs, refresh/logout, and account/session SQL.
  `server/src/main.rs` parses the configured API origin; `auth/proof.rs` derives
  expected URLs from that origin and the proof purpose. `server/src/router.rs`
  registers the access-protected `modules/profile/` routes.
- Frontend: `modules/auth/` owns login, key storage/proofs, refresh/logout,
  the sidebar account menu, shared failure handler, and session-ended notices.
  `modules/profile/` owns the profile query and UI, uploads, and deletion flow.
  `shared/api/request.ts` owns bounded access recovery; `router.tsx` registers the
  handler; `integrations/tanstack-query/root-provider.tsx` owns retry defaults.

Ending one session blocks refresh, not copied access JWTs already issued: those
may work for up to 10 minutes because access authentication does not read sessions.
Future sensitive writes need explicit active-session checks. Non-exportability
does not prevent XSS/device compromise or same-origin JavaScript asking Web Crypto
to sign. Cleanup of expired sessions/orphaned keys, rate limits, automated
regression/security coverage, and DBSC remain deferred.

References: [Web Crypto](https://www.w3.org/TR/webcrypto/),
[Web Locks](https://www.w3.org/TR/web-locks/),
[JWT validation](https://www.rfc-editor.org/rfc/rfc8725.html),
[proof/replay considerations](https://www.rfc-editor.org/rfc/rfc9449.html#section-11.1),
[Google verification](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
