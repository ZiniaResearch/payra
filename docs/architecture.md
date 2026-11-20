# Architecture

Payra has a React/TypeScript TanStack Start application in `ui/` and a Rust/Axum
modular monolith in `server/`, using SQLx/PostgreSQL. Use Bun for UI tooling.
See [system design](system-design.md) for product rules and [authentication](auth.md)
for profile, refresh/logout, errors, security limits, and manual checks.

## Current state

Implemented:

- Google authorization-code login, account creation/reuse, P-256 browser keys,
  separate access/refresh JWTs, signed rotation/reuse revocation, and signed logout.
- Local access-JWT middleware without session-table reads, protected
  `GET /api/profile`, shared terminal Query/Mutation auth-error handling, and
  session-ended tab synchronization.
- Google-initialized name/avatar, a randomly selected media-hosted cover, and shared
  profile reads for the sidebar and modal. Later sign-ins preserve saved values.
- BoardUI name and preset-cover editing, optional Garage-backed avatar uploads,
  and account deletion with a browser-key proof and best-effort avatar removal.
- Full source-owned BoardUI sidebar, independent account skeleton/email/retry UI,
  theme controls, and one mounted signed-logout hook.
- Client-rendered pathless `_protected` layout with empty Schedule SMS, Messages,
  Analytics, and Buy Credits page shells, without a blocking auth loader,
  IndexedDB-presence gate, or identity-demo API.
- PostgreSQL 18 Compose service and account/session migrations.

Deferred:

| Area | Current boundary |
| --- | --- |
| Header/mobile navigation | `Header.tsx` is an unused stub; no integration or mobile drawer. |
| Workspace/settings/support | Sidebar UI is installed; product actions are not connected or persisted. |
| Profile | No standalone `/profile` page, avatar cropping tools, or custom cover uploads. |
| Sign-in routing | Redirecting signed-in browsers away from `/login` and requiring sign-out before a new login are planned, not enforced yet. |
| Product pages | `/` is a public placeholder. Scheduling/messages/billing/analytics routes have empty shells only. Pricing, consent, recipients, and product data/UI remain planned. |
| Scheduling/payments | No worker, scheduling/credit/payment APIs, provider integrations, or product migrations; billing currently has only a frontend page shell. |
| Deployment/hardening | No application Dockerfiles/Caddy stack, separate worker/migration subcommands, auth rate limits, or automated auth regression coverage. |

No request-session tracker, per-login generation, additional cancellation
controller, or session-started notification is part of this design.

## Local development

Prerequisites: Bun, Rust, Docker Compose, and a PostgreSQL-capable SQLx CLI.
Migrations use PostgreSQL 18's `uuidv7()`.

From the repository root:

```bash
cp -i server/.env.example server/.env
cp -i ui/.env.example ui/.env
docker compose up -d db
```

| File | Local settings |
| --- | --- |
| `server/.env` | Example `DATABASE_URL`, `BIND_ADDR=127.0.0.1:8000`, `UI_ORIGIN=http://localhost:3000`, `API_ORIGIN=http://localhost:8000`, required `MEDIA_BASE_URL`, Google client ID/secret, and distinct access/refresh JWT secrets. |
| `ui/.env` | The same public client ID as `VITE_GOOGLE_CLIENT_ID`; `VITE_API_URL=http://localhost:8000`. |

Generate each JWT secret independently, for example with `openssl rand -base64 32`,
and retain both across restarts. Never put server secrets in `VITE_` variables.
Configure the Google Web client's authorized frontend origin with the matching
client ID. [Google client setup](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid)

`MEDIA_BASE_URL` is the browser-facing read URL for shared profile images, not
Garage's authenticated S3 endpoint. The local example uses
`http://payra-presets.web.garage.localhost:3902`; use the public HTTPS media URL
on the VPS. Seed the bucket root with `default-profile.webp` and `cover-1.webp`
through `cover-3.webp` from `ui/public/profile/`. Payra does not need Garage
credentials to display these assets.

The local Garage Compose service expects `payra-media/.env` and
`payra-media/garage.toml` to be supplied locally. Both files are ignored by Git.

To enable **avatar uploads** locally, use the running `payra-media` Compose
project. From the repository root:

```bash
docker compose -f payra-media/compose.yaml exec garage /garage bucket create payra-avatars
docker compose -f payra-media/compose.yaml exec garage /garage key create payra-avatar-app
docker compose -f payra-media/compose.yaml exec garage /garage bucket allow payra-avatars --read --write --key payra-avatar-app
docker compose -f payra-media/compose.yaml exec garage /garage bucket website --allow payra-avatars
```

The key creation command prints a secret; put it only in `server/.env`. Set the
five avatar settings shown in `server/.env.example`, using that bucket's key, not
the preset or administrative key. The signing region defaults to `garage`;
override `AVATAR_S3_REGION` if it differs in your Garage config. With no
`AVATAR_S3_ENDPOINT`, uploads and automatic avatar removal are disabled. The
local bucket website exposes photos to anyone with their URL. Use a disposable
account when testing [account deletion](auth.md#delete-account).

From `server/`, apply migrations and start Axum; both load its `.env`:

```bash
sqlx migrate run
cargo run
```

`cargo run` does not migrate automatically. `serve`, `worker`, and `migrate` are
not current CLI subcommands. `GET http://127.0.0.1:8000/health` returns 200 when its
database check succeeds, or 503 when the two-second check fails/times out.

In another terminal, from `ui/`:

```bash
bun install
bun --bun run dev
```

Open `http://localhost:3000/login`. Use `localhost` consistently for browser-facing
URLs, even though services bind to loopback. Requests are credentialed/cross-origin;
Vite has no `/api` proxy. CORS allows the configured UI origin, GET/HEAD/POST/
PATCH/PUT/DELETE, content type, and `X-Requested-With`. Shared Origin middleware
requires the exact UI origin and `X-Requested-With: XmlHttpRequest` on writes.
GET/HEAD may omit Origin but reject a mismatching supplied value; CORS handles preflight.

Browser signing needs Web Crypto, IndexedDB, and Web Locks in a secure context.
Cookies remain `Secure` locally; use HTTPS/matching origins if the browser rejects
them on localhost. Production requires public HTTPS origins.

## Server layout

```text
server/
├── migrations/                 Accounts, sessions, avatar keys, deletion challenges
└── src/
    ├── main.rs                 Environment, Tokio, API startup, API origin
    ├── config.rs               Required settings and optional avatar storage
    ├── db.rs                   Shared SQLx pool
    ├── router.rs               Public/protected groups and CORS
    ├── state.rs                Shared state and pool extraction
    └── modules/
        ├── mod.rs
        ├── auth/
        │   ├── mod.rs
        │   ├── routes.rs       Google login and auth route registration
        │   ├── repository.rs   Account/session SQL
        │   ├── google.rs       Code exchange and ID-token verification
        │   ├── tokens.rs       JWT issuance/verification
        │   ├── middleware.rs   Local access verification
        │   ├── browser_key.rs  Public-key validation
        │   ├── cookies.rs
        │   ├── proof.rs        ES256 purpose/URL/hash verification
        │   ├── refresh.rs      Rotation and reuse revocation
        │   └── logout.rs       Signed/idempotent logout
        ├── health/
        │   ├── mod.rs
        │   └── routes.rs       Database health
        └── profile/
            ├── mod.rs
            ├── routes.rs       Profile reads, updates, and route registration
            ├── repository.rs   Account-scoped SQL and transaction locks
            ├── presets.rs      Shared cover catalog and fallback avatar
            ├── avatar.rs       Validate, re-encode, and publish an upload
            ├── avatar_storage.rs  Scoped Garage client and best-effort deletes
            └── deletion.rs     Browser proof and account deletion
```

`main.rs` supplies the pool, Google verifier, token signer, origins, media base
URL, and optional avatar storage through `AppState`. The auth proof verifier combines the API
origin with its purpose-specific refresh, logout, or deletion path. Access
middleware receives only the signer, not the database pool. Profile reads,
name/cover edits, and avatar uploads do not check the session table. Preparing
and authorizing account deletion do.

Keep Rust modules feature-oriented: `routes.rs` handles HTTP, `repository.rs`
handles SQL, and business functions own full transaction boundaries. Future
recipients, scheduling, billing, worker, and provider code follow this ownership;
do not create empty scaffolding or one global repository.

## UI layout

Current application structure; generic BoardUI files are abbreviated:

```text
ui/src/
├── router.tsx                       Query/router integration and auth handler
├── routeTree.gen.ts                  Generated; do not edit
├── routes/
│   ├── __root.tsx                   HTML and global styles
│   ├── index.tsx                    / placeholder
│   ├── login.tsx                    /login
│   └── _protected/
│       ├── route.tsx                Sidebar and Outlet; ssr: false
│       ├── analytics.tsx            /analytics
│       ├── billing.tsx              /billing — Buy Credits shell
│       └── messages/
│           ├── index.tsx            /messages — list shell
│           └── new.tsx              /messages/new — Schedule SMS shell
├── modules/
│   ├── layout/
│   │   ├── Sidebar.tsx              Payra navigation/composition
│   │   └── Header.tsx               Unused stub; deferred
│   ├── auth/
│   │   ├── GoogleSignInButton.tsx
│   │   ├── AccountMenu.tsx          Profile state and signed logout
│   │   ├── api.ts                   Existing Google login request
│   │   ├── refresh.ts
│   │   ├── logout.ts
│   │   ├── use-logout.ts            Action and private tab-sync hook
│   │   ├── handle-auth-failure.ts
│   │   └── ...                     Browser keys, storage, proofs, events
│   ├── profile/
│   │   ├── ProfileModal.tsx         Profile and separate editors/dialogs
│   │   ├── queries.ts               Profile and preset reads/cache keys
│   │   └── mutations.ts             Profile writes and deletion cleanup
│   ├── analytics/
│   │   └── AnalyticsPage.tsx        Initially empty
│   ├── messages/
│   │   ├── MessagesPage.tsx         Empty list shell
│   │   └── ScheduleMessagePage.tsx  Empty scheduling shell
│   └── billing/
│       └── BuyCreditsPage.tsx       Empty credits shell
├── components/                      Source-owned BoardUI application/base/foundations
├── shared/api/
│   ├── client.ts                    Bare Axios client
│   └── request.ts                   Protected requests and access recovery
├── integrations/tanstack-query/      Query defaults and devtools
├── styles.css
├── styles/                          BoardUI theme/globals/typography
└── utils/                           Generic UI helpers
```

### Ownership and future routes

| Location | Responsibility |
| --- | --- |
| `routes/` | URLs, parameters/search validation, route configuration, arranging module components. |
| `modules/layout/` | Payra-specific sidebar/header composition and navigation. |
| `modules/<feature>/queries.ts` | Read requests, reusable query options, and cache keys. |
| `modules/<feature>/mutations.ts` | Write requests and cache updates when writes exist. |
| `modules/<feature>/*.tsx` | Feature UI with independent loading/error states. |
| `components/` | Generic BoardUI blocks/primitives; not Payra HTTP behavior. |
| `shared/api/`, Query integration, `router.tsx` | Transport, refresh coordination, retry defaults, and shared auth-error registration. |

`auth/api.ts` stays for Google login. Other features call HTTP directly in
`queries.ts`/`mutations.ts`; extract helpers only for genuine reuse. No mutation
file is needed for the profile read.

The sidebar order is **Schedule SMS** (`/messages/new`), **Messages** (`/messages`),
**Analytics** (`/analytics`), then **Buy Credits** (`/billing`). These independent
routes are empty page shells under the pathless layout, not a dashboard prefix.
Trailing slashes are normalized for sidebar selection. Scheduling, message
statuses/data, and credit purchase UI are not implemented; no new API calls are
made by these shells.

`/profile`, `/messages/:messageId`, and `/recipients` remain planned protected
pages; pricing and `/consent/:token` are planned public pages. Add read/write files
only when needed. The existing profile API does not mean a `/profile` page exists.

### Rendering and sidebar

`_protected/route.tsx` arranges `Sidebar` and `<Outlet />` with `ssr: false`.
After sign-in, the sidebar and empty Schedule SMS page appear immediately. The
account query and future page sections load independently: each has its own
skeleton/result/retry UI, without a page-wide awaited loader or `Promise.all`
gate. Signed-out visitors can briefly see the empty shell; backend JWT
verification is the security boundary,
not layout grouping or local storage.

The full BoardUI sidebar keeps animated collapse, `Cmd/Ctrl+K` navigation search,
theme controls, and popovers. Payra supplies actual navigation/account contents
through `modules/layout/Sidebar.tsx`. The top popover represents Payra; the bottom
shows real email/loading/unavailable state, Account Details, retry, and a red
BoardUI Sign out button without a branding footer. Account Details opens a
profile-only BoardUI modal with the saved avatar overlapping an edge-to-edge
cover halfway, the saved name/edit pen, and the read-only email. Failed avatar
images fall back to initials; missing/failed covers retain a semantic background.
A cover edit control sits at the cover's bottom-right, and a
red Delete account button sits at the modal's bottom-right. Name/image editing
and deletion stay disabled until supported; there is no navigation rail.
One account instance mounts tab
synchronization. Header/mobile drawer are deferred. Support/Settings rows are
removed; installed workspace/settings examples are not saved product features.

Unused template image assets are removed. Generic fallback avatars/file icons
use initials/Remix icons; optional artwork can be supplied when actually needed.

Follow [BoardUI rules](../ui/AGENTS.md): installed components first, semantic
colors/composite typography, logical direction-aware layout, accessible controls,
and existing scroll-fade helpers. Install missing components with
`bunx boardui@latest add component-name`; do not replace the full sidebar with a
lookalike or migrate away from Biome.

Vite regenerates routes during development/builds. `generate-routes` currently
runs the same build as `build`, not a separate route-only generator.

## API and data

| Implemented endpoint | Contract |
| --- | --- |
| `GET /health` | Database-backed health. |
| `POST /api/auth/google` | Google code/public key → cookies and session/hash metadata. |
| `POST /api/auth/refresh` | Signed proof → rotated cookies and session/hash metadata. |
| `POST /api/auth/logout` | Signed proof → end one login, clear cookies, 204. |
| `GET /api/profile` | Verified account → saved profile, selected preset ID and avatar availability, `no-store`. |
| `GET /api/profile/covers` | Server-owned `{ id, label, url }[]` catalog; no DB lookup. |
| `PATCH /api/profile` | Optional `name` and/or `cover_id` → updated profile. |
| `PUT /api/profile/avatar` | Image bytes → save a WebP in Garage and return the updated profile. |
| `POST /api/profile/deletion` | Active login → issue a 60-second nonce for that session. |
| `DELETE /api/profile` | Signed browser proof → delete the account and sessions, clear cookies, and attempt avatar removal after commit. |

Auth routes/health are outside access middleware; profile is inside it. The old
identity-demo endpoint is removed. Detailed failures and security boundaries are
in [authentication](auth.md).

The profile/auth schema has `accounts`, `auth_sessions`, and
`profile_deletion_challenges`. Account deletion cascades to sessions and
challenges. An uploaded avatar's object key lives on the account; there is no
avatar cleanup table or polling. First login saves the verified Google name/photo
or `Account`/the default avatar and randomly selects one of three covers. Shared
asset URLs use `MEDIA_BASE_URL`. Later logins update email only; existing saved
URLs are not rewritten. Keep the bundled source files for seeding and for older
accounts that still reference them. No separate Google-default/override columns
are stored. Time zones and sending holds are not stored yet.

### Planned product APIs and schema

These are targets, not registered routes or applied migrations:

| Area | Endpoints | Data/constraints |
| --- | --- | --- |
| Recipients/consent | `GET/POST /api/recipients`; `GET/POST /api/consent/{token}` | Account-scoped recipients; unique account/phone and account/ID; expiring invitation-token digests, consent evidence, and provider suppressions. Open links read only; consent requires POST. |
| Messages | `GET/POST /api/messages`; `PATCH /api/messages/{id}`; `POST /api/messages/{id}/cancel` | Account-scoped messages/jobs, composite recipient ownership FK, unique submission keys, time zones/send windows, claim/lease/attempt fields, and separate submission/delivery status. |
| Billing | Bundle/balance reads; order list/create/detail; payment return/webhooks | Bundle catalog, immutable order amount/credit snapshots, unique merchant/gateway transactions, nonnegative available/reserved balances, append-only idempotent ledger. |
| Delivery | SMS webhook/dispatch/reconciliation | Unique receipt events, provider IDs, unmatched-receipt retention, and unknown-outcome tracking. |

Extend CORS/methods, authorization, origin checks, validation, and migrations as
features are built. Use `timestamptz`, exact integer minor-unit money, scoped
history indexes, and partial due-job/lease indexes.

Planned transaction boundaries:

- **Schedule/cancel:** lock active session, then balance, then message where needed.
  Reserve/release credit and write message/ledger/balance atomically; a submission
  key prevents duplicate scheduling.
- **Payment:** verify provider details outside the DB transaction, then confirm
  the order and grant credits once inside it. Reconcile delayed notifications;
  browser redirects are informational, not payment authority.
- **Dispatch:** claim due jobs with `FOR UPDATE SKIP LOCKED`; commit before the
  provider call. Recheck consent/suppression/holds/limits/window; record only the
  current claim. Retry definite temporary rejections, not unknown outcomes.
- **Recovery:** expire queued jobs and release once; interrupted sends become
  unknown with credits reserved. Reconcile acceptance before resubmission; use
  provider idempotency keys where supported.

Use fake SMS/provider adapters and a controllable clock when building this work.
The planned milestones are one scheduled text, credit/concurrency guarantees,
sandbox wallet checkout, then live SMS/deployment; acceptance rules are in
[system design](system-design.md#launch-checks).

## Profile implementation TODO

Checked items are implemented in code, not necessarily live-verified.

### 1. Google defaults and profile read

- [x] Capture optional name/photo claims from the verified Google identity; retain
  default name/avatar and failed-image initials fallbacks.
- [x] Add required `name`, `avatar_url`, and `cover_url` columns for fresh
  development accounts; initialize them at account creation, without a backfill.
- [x] Preserve saved name/avatar/cover on later sign-ins by updating email only.
  Keep email read-only.
- [x] Return the saved profile through `GET /api/profile`.
- [x] Render the saved profile in the sidebar and modal through the existing
  shared account query, without blocking page rendering.
- [ ] Manually verify first login, default assets, failed photos, reloads,
  repeat sign-ins, and switching accounts.

### 2. Name editing and preset covers

- [x] Host the shared default avatar and three covers in the local Garage preset
  bucket; use `MEDIA_BASE_URL` for new accounts and randomly choose a cover.
- [ ] Update existing bundled URLs without changing Google avatar URLs before
  removing the old serving paths.
- [x] Use one server catalog for defaults, listing covers, and resolving `cover_id`.
- [x] Add `PATCH /api/profile` for name and cover with input validation, account
  scoping and the shared Origin check, without session-table reads.
- [x] Add BoardUI name and cover editors with Save/Cancel, pending/error states,
  and shared profile-cache updates from successful save responses.
- [x] Keep profile UI, `queries.ts` and `mutations.ts` in `ui/src/modules/profile/`,
  with route composition and generic BoardUI components separate.

### 3. Self-hosted Rust media service and avatar uploads

- [x] Pilot self-hosted Garage, a Rust-based S3-compatible store, for local
  preset image uploads and reads. Payra will own media validation and processing.
- [ ] Deploy Garage alongside Payra on the VPS with persistent storage, HTTPS
  reads, private S3 access, quotas, off-VPS backups, monitoring, and a tested
  restore procedure.
- [x] Use a separate avatars bucket and scoped key; the browser does not receive
  S3 credentials or control the object key.
- [x] Add access-JWT-only `PUT /api/profile/avatar` with bounded image input/
  decoding and WebP output. Save the display URL and owned object key after
  Garage accepts the file.
- [x] Add BoardUI file selection, preview, pending/errors, and profile-cache updates.
- [x] After the new profile commits, try deleting the replaced object in the
  background with one retry. Failed cleanup can leave an unused file.
- [ ] Add photo cropping only if needed. Measure upload and idle storage resource use.
- [ ] Verify image and video upload/delete support in the media service and
  document future video integration; this stage enables avatars, not a new
  product video-upload feature.

### 4. Account deletion

- [x] Require inline confirmation in the BoardUI profile footer, without a Google prompt.
- [x] Verify a browser-key proof and a nonce bound to the current active Payra
  session before deleting.
- [x] Delete the profile, all sessions and challenges in one DB transaction.
  After commit, attempt to delete the current uploaded avatar with one retry.
  Never delete shared presets or Google photos.
- [x] Clear cookies and use the existing browser-key/cache and session-ended
  cleanup without new session tracking.
- [ ] Publish a backup expiry/deletion policy before production. Add deletion
  and retention rules as product tables are introduced; there are no billing,
  message, or recipient records yet.

### 5. Manual verification

- [ ] Verify Google defaults/missing claims, fresh-account initialization, read-only
  email, edit persistence across sign-ins, preset validation, and sidebar/modal
  cache consistency.
- [ ] Check account isolation, avatar validation/limits, service outages,
  interrupted/replaced uploads, concurrent edits, and best-effort removal.
  A failed removal must not undo a saved profile change.
- [ ] Verify browser-proof and active-session checks, deletion across all
  sessions/tabs, stale JWT rejection for sensitive actions, concurrent work,
  best-effort avatar removal, retention disclosures, and empty-account re-registration.

## Docker and VPS

The root Compose runs PostgreSQL; `payra-media/compose.yaml` runs the local Garage
pilot. Production deployment is planned:

- Same-origin HTTPS through Caddy, `/api/*` forwarded unchanged to Axum, with a
  web service, worker, database, and one-shot migrations.
- Explicitly configure/verify a compatible TanStack Start static/SPA output before
  serving client files plus page fallbacks through Caddy. Current `tanstackStart()`
  configuration does not establish that deployment; route-level `ssr: false` is
  not a whole-app static build. If retaining Start's server runtime, route page
  requests to it instead. Do not deploy only client assets and assume it works.
- Use internal database DNS, a PostgreSQL health check, persistent volumes,
  backups/restore checks, multi-stage non-root images, secret env files excluded
  from build contexts, and runtime server secrets. The public Google ID is a UI
  build input; public API/proof URLs must match HTTPS deployment.
- Run Garage with private S3 access and a Caddy HTTPS read endpoint. Keep shared
  presets separate from uploads, set bucket quotas, and verify restart
  persistence, MP4 seeking, resource use, and off-VPS backup restore.
- Add structured diagnostics, bounded provider calls, graceful shutdown, and
  queue/unknown-outcome monitoring without logging secrets, proofs, message bodies,
  or full phone numbers.

## Verification and merge checklist

Snapshot: 2026-11-19. Backend formatting/strict Clippy, UI TypeScript, targeted
formatting/lint, and the production client/server build pass after profile wiring. Full
Biome still reports **20 errors and 3 warnings** in installed BoardUI components.
Live auth/profile/cross-tab verification remains outstanding.

Automated auth tests were deferred in favor of the documented manual checks.
No application servers were started for verification. Local commits do not
approve a push or merge; passing compilation is not live verification.

Run from `ui/`:

```bash
bun run typecheck
bun run check
bun --bun run build
```

`bun run fix` applies safe fixes/formatting; review changes. `lint` and `format`
check their respective rules. From `server/`:

```bash
cargo fmt --check
cargo clippy --all-targets -- -D warnings
```

Before merging:

- [ ] Fix UI lint/formatting without stripping BoardUI or adding unrelated auth.
- [ ] Rerun the full UI checks/build; rerun backend checks if backend changes.
- [ ] Complete [auth/profile manual checks](auth.md#manual-verification), including
  direct visits, expiry, two tabs, logout ordering, transient errors, and switching
  accounts.
- [ ] Review intended files; exclude real environment/browser secrets, build
  output, and unrelated user work.
- [ ] Commit/merge only when requested and checks are accepted.
