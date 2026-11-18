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
- Google-initialized name/avatar, a randomly selected bundled cover, and shared
  profile reads for the sidebar and modal. Later sign-ins preserve saved values.
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
| Profile | Saved name/avatar/cover and email/ID read; no editing, uploads, deletion, or `/profile` page. |
| Product pages | `/` is a public placeholder. Scheduling/messages/billing/analytics routes have empty shells only. Pricing, consent, recipients, and product data/UI remain planned. |
| Scheduling/payments | No worker, scheduling/credit/payment APIs, provider integrations, or product migrations; billing currently has only a frontend page shell. |
| Deployment/hardening | No application Dockerfiles/Caddy stack, worker/migration subcommands, scheduled cleanup, auth rate limits, or automated auth regression coverage. |

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
| `server/.env` | Example `DATABASE_URL`, `BIND_ADDR=127.0.0.1:8000`, `UI_ORIGIN=http://localhost:3000`, `API_ORIGIN=http://localhost:8000`, Google client ID/secret, and distinct access/refresh JWT secrets. |
| `ui/.env` | The same public client ID as `VITE_GOOGLE_CLIENT_ID`; `VITE_API_URL=http://localhost:8000`. |

Generate each JWT secret independently, for example with `openssl rand -base64 32`,
and retain both across restarts. Never put server secrets in `VITE_` variables.
Configure the Google Web client's authorized frontend origin with the matching
client ID. [Google client setup](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid)

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
Vite has no `/api` proxy. CORS currently allows the configured UI origin, GET/POST,
content type, and `X-Requested-With`. Auth POSTs check the exact Origin and require
`X-Requested-With: XmlHttpRequest`.

Browser signing needs Web Crypto, IndexedDB, and Web Locks in a secure context.
Cookies remain `Secure` locally; use HTTPS/matching origins if the browser rejects
them on localhost. Production requires public HTTPS origins.

## Server layout

```text
server/
├── migrations/                 accounts and auth_sessions
└── src/
    ├── main.rs                 Environment, Tokio, API startup, proof URLs
    ├── config.rs               Required settings
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
            ├── routes.rs       Profile response/error mapping
            └── repository.rs   Account-scoped profile read
```

`main.rs` supplies pool, Google verifier, token signer, UI origin, and configured
proof URLs through `AppState`. Access middleware receives only the signer, not
the database pool. Health/profile extract the pool for their own reads.

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
│   │   ├── queries.ts               Profile read/options/cache key
│   │   ├── refresh.ts
│   │   ├── logout.ts
│   │   ├── use-logout.ts            Action and private tab-sync hook
│   │   ├── handle-auth-failure.ts
│   │   └── ...                     Browser keys, storage, proofs, events
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
The sidebar and empty analytics page appear immediately. The account query and
future page sections load independently: each has its own skeleton/result/retry
UI, without a page-wide awaited loader or `Promise.all` gate. Signed-out visitors
can briefly see the empty shell; backend JWT verification is the security boundary,
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
| `GET /api/profile` | Verified account → `{ account_id, email, name, avatar_url, cover_url }`, `no-store`. |

Auth routes/health are outside access middleware; profile is inside it. The old
identity-demo endpoint is removed. Detailed failures and security boundaries are
in [authentication](auth.md).

Only `accounts` (UUIDv7 ID, unique Google subject, email, required name/avatar URL/
cover URL, creation time) and
`auth_sessions` (account FK/cascade, public key, positive counter, deadlines,
constraints/index) exist. First login saves the verified Google name/photo or
`Account`/the bundled default avatar and randomly selects one of three bundled
covers. URLs for bundled assets use the configured UI origin. Later logins update
email only; no separate Google-default/override columns are stored. The new
required columns target fresh development accounts, not a production backfill.
Time zones and sending holds are not stored yet.

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

Implement these stages in order, one step at a time. Checked items are implemented
in code, not live-verified. Editing/deletion controls remain disabled.

### 1. Google defaults and profile read

- [x] Capture optional name/photo claims from the verified Google identity; retain
  default name/avatar and failed-image initials fallbacks.
- [x] Add required `name`, `avatar_url`, and `cover_url` columns for fresh
  development accounts; initialize them at account creation, without a backfill.
- [x] Preserve saved name/avatar/cover on later sign-ins by updating email only.
  Keep email read-only.
- [x] Expand `GET /api/profile` to return
  `{ account_id, email, name, avatar_url, cover_url }`.
- [x] Render the saved profile in the sidebar and modal through the existing
  shared account query, without blocking page rendering.
- [ ] Manually verify first login, default assets, failed photos, reloads,
  repeat sign-ins, and switching accounts.

### 2. Name editing and preset covers

- [x] Bundle a default avatar and three cover images under `ui/public/profile/`;
  randomly choose a cover on account creation.
- [ ] Define the server-validated cover URL allowlist for editing. Covers are
  selected from our presets, not uploaded or arbitrary URLs.
- [ ] Implement proposed `PATCH /api/profile` for name and cover URL, with
  server-side trimming/length checks, preset validation, account isolation, and
  deliberate active-session/origin checks for writes.
- [ ] Add BoardUI name and cover editors with Save/Cancel, pending/error states,
  and shared profile-cache updates after successful saves.
- [ ] Put reads in `ui/src/modules/auth/queries.ts` and writes in a new
  `mutations.ts`; keep route composition and generic BoardUI components separate.
  Extend CORS for the required PATCH, PUT, and DELETE methods as endpoints land.

### 3. Self-hosted Rust media service and avatar uploads

- [ ] Evaluate and select an efficient, open-source Rust media upload
  service/application supporting images and videos. No project is selected yet.
  Check licensing, maintenance, authenticated upload/delete APIs, processing,
  resource limits, storage, and reliable deletion before choosing.
- [ ] Deploy it as a separate service on our VPS with persistent storage, HTTPS,
  private management credentials/network access, quotas, backups, monitoring, and
  a documented update/restore procedure. This replaces the earlier direct
  S3-compatible-storage recommendation; finalize storage behind the chosen service.
- [ ] Define Payra-to-media authentication and account ownership. Clients must
  not receive service-admin credentials or choose another account's media key.
  Require authenticated, authorized uploads and deletion. Keep display URLs in
  `avatar_url` and retain any service-specific identifier needed for reliable cleanup.
- [ ] Implement proposed `PUT /api/profile/avatar` through the selected service:
  enforce byte/dimension limits, verify decoded content, initially allow
  JPEG/PNG/WebP rather than SVG, re-encode/strip metadata, and generate media keys
  server-side. Bound processing time/memory and configure image/video limits.
- [ ] Add BoardUI file selection, preview/crop, upload progress/pending/errors,
  and effective-profile cache updates. Save owned media references in PostgreSQL,
  not binary files or uploads in Git.
- [ ] Handle failed uploads/database writes, abandoned files, and avatar
  replacement with durable cleanup retries and concurrency-safe ownership checks.
- [ ] Verify image and video upload/delete support in the media service and
  document future video integration; this stage enables avatars, not a new
  product video-upload feature.

### 4. Account deletion

- [ ] Define deletion scope and retention: remove live account-owned profile,
  sessions, uploads, and future messages/recipients/jobs; explicitly document
  backup expiry and any legally retained payment records.
- [ ] Add a BoardUI destructive confirmation explaining irreversibility, requiring
  typed `DELETE` and fresh Google reauthentication, with pending/error states.
- [ ] Implement proposed `DELETE /api/profile`: verify a fresh Google code matches
  the current account subject; check Origin/required request header and the active
  session. Deliberately expose the already-validated JWT session ID for sensitive
  writes; keep ordinary access middleware database-free.
- [ ] Transactionally record durable media-cleanup jobs, delete account-owned
  data/sessions, and cancel queued work. Define cascade/locking rules as each
  product table is added so deletion cannot race with new writes or dispatch.
- [ ] Ensure cleanup jobs survive account deletion and retry external media
  deletion failures. Database deletion and media deletion cannot share a
  transaction; document pending cleanup rather than claiming immediate removal.
- [ ] After committed deletion, clear cookies and reuse existing browser-key,
  Query/Router cache, and session-ended tab cleanup/navigation. Handle an already
  deleted account safely; do not add request-session tracking, login generations,
  or session-started notifications.
- [ ] Prevent still-valid access JWTs from authorizing sensitive actions against
  deleted accounts/sessions. Explain that Payra deletion does not delete Google
  and that signing in again normally creates a new, empty account.

### 5. Manual verification

- [ ] Verify Google defaults/missing claims, fresh-account initialization, read-only
  email, edit persistence across sign-ins, preset validation, and sidebar/modal
  cache consistency.
- [ ] Check account isolation, expired/revoked sessions, upload validation/limits,
  service outages, interrupted/replaced uploads, concurrent edits, and eventual
  orphan/replacement cleanup.
- [ ] Verify fresh reauthentication and subject matching, deletion across all
  sessions/tabs, stale JWT rejection for sensitive actions, concurrent work,
  cleanup retries, retention disclosures, and empty-account re-registration.

## Docker and VPS

Current Compose runs only PostgreSQL; everything here is planned:

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
