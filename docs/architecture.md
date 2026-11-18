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
| Profile | Email/ID read only; no editing, display-name/photo storage, or `/profile` page. |
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
            └── repository.rs   Account-scoped email read
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
profile-only BoardUI modal with a centered placeholder avatar overlapping an
edge-to-edge placeholder cover halfway, a name placeholder/edit pen, and the
read-only email. A cover edit control sits at the cover's bottom-right, and a
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
| `GET /api/profile` | Verified account → `{ account_id, email }`, `no-store`. |

Auth routes/health are outside access middleware; profile is inside it. The old
identity-demo endpoint is removed. Detailed failures and security boundaries are
in [authentication](auth.md).

Only `accounts` (UUIDv7 ID, unique Google subject, email, creation time) and
`auth_sessions` (account FK/cascade, public key, positive counter, deadlines,
constraints/index) exist. Display names, photos, time zones, and sending holds
are not stored yet.

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

Snapshot: 2026-11-17. Backend formatting/strict Clippy, UI TypeScript, formatting,
and the production client/server build pass after template-asset cleanup. Full
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
