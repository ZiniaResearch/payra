# SMS scheduling SaaS architecture

Payra has two applications in one repository. `ui/` is a React and TypeScript app built with Vite. `server/` is the Rust modular monolith. Its `serve` command runs the Axum API, `worker` submits scheduled messages, and `migrate` updates the database schema. These commands ship in one Rust image. Caddy serves the compiled React app over HTTPS and forwards API requests to Axum. PostgreSQL stores accounts, schedules, credits, and payment history.

See the [system design](system-design.md) for customer behavior, bundle rules, scheduling defaults, and launch checks.

## Runtime components

```mermaid
flowchart LR
    Browser["Customer browser"] --> Proxy["Caddy"]
    Proxy -->|React files and page URLs| Ui["Compiled React UI"]
    Proxy -->|"/api/*"| Web["Axum API"]
    Browser --> Checkout["Hosted checkout"]
    Checkout -->|Payment notification| Proxy
    Web -->|Validate payment| Checkout
    Web -->|OpenID Connect| Google["Google"]
    Web --> Database[("PostgreSQL")]
    Worker["SMS worker"] --> Database
    Worker --> Sms["SMS provider"]
    Sms -->|Delivery and opt-out events| Proxy
```

The React app renders pages and calls the API. Axum owns sessions, authorization, validation, and HTTP responses. The worker owns scheduled dispatch. The web and worker processes call application functions backed by SQLx. Payment and SMS adapters translate provider responses into local outcomes. Start with a fake SMS adapter and a clock you can set in tests. [Axum routing and state](https://docs.rs/axum/latest/axum/)

## Repository layout

```text
payra/
├── compose.yaml
├── docs/
├── deploy/
│   └── caddy/
│       ├── Caddyfile       HTTPS, React page fallback, and API proxy
│       └── Dockerfile      Builds React and copies its output into Caddy
├── server/
│   ├── .env.example       Local Rust settings template
│   ├── Cargo.toml
│   ├── Cargo.lock
│   ├── Dockerfile          Shared image for web, worker, and migrations
│   ├── migrations/        Versioned PostgreSQL schema changes
│   └── src/               Axum, business rules, and the worker
└── ui/
    ├── package.json
    ├── vite.config.ts
    └── src/               React application
```

## Local development

On a fresh checkout, copy the example settings and start PostgreSQL from the Payra root. Then start Axum from `server/` so it finds `server/.env`.

```bash
cp -i server/.env.example server/.env
docker compose up -d db
cd server
cargo run
```

In a second terminal, run `curl -i http://127.0.0.1:3000/health`. Expect `200 OK` when the server can query PostgreSQL. The local template connects to PostgreSQL through its published host port at `127.0.0.1:5432` and binds Axum to `127.0.0.1:3000`.

## Server layout

```text
server/src/
├── main.rs                Wires dependencies and starts the selected command
├── config.rs              Configuration module when startup settings grow
├── db.rs                  Creates the shared SQLx PostgreSQL pool
├── web.rs                 Combines routers and supplies shared web state
├── health.rs              Database-backed GET /health endpoint
├── auth.rs                Authentication module entry point
├── auth/
│   ├── routes.rs          Sign-in, callback, refresh, logout, and account endpoints
│   ├── google.rs          OpenID Connect redirect and token verification
│   └── sessions.rs        Session storage, refresh rotation, and revocation
├── recipients.rs          Recipient records, consent, and opt-outs
├── scheduling.rs          Scheduling module entry point
├── scheduling/
│   ├── routes.rs          Message list, create, edit, and cancel endpoints
│   └── messages.rs        Scheduling rules, message SQL, and job transitions
├── billing.rs             Billing module entry point
├── billing/
│   ├── routes.rs          Bundle, order, balance, and payment endpoints
│   ├── orders.rs          Checkout orders, payment confirmation, and reconciliation
│   └── credits.rs         Credit grants, reservations, consumption, and releases
├── worker.rs              Polls for due jobs, attempts sends, and handles recovery
└── providers/
    ├── mod.rs             Declares the provider modules
    ├── payments.rs        Hosted checkout and gateway API calls
    └── sms.rs             Fake sender, live sender, and receipt verification
```

The initial server has four sibling files. `main.rs` loads `.env` before starting Tokio, reads `DATABASE_URL` and `BIND_ADDR`, and starts the listener. `db.rs` creates a pool that connects on demand. `web.rs` merges the health router and gives Axum the pool with `with_state`. `health.rs` registers the route and obtains the pool from Axum when a request arrives.

Create future feature files when you build those features. A feature can expose `routes()` from a single file and keep that entry point when it grows a directory.

Each HTTP feature exposes a small router. A handler reads the request and authenticated account, calls the feature function, and maps its result to HTTP. Keep SQL close to the feature that owns it. For example, scheduling creates the message and calls billing to reserve a credit inside the same transaction. The worker calls scheduling to claim and settle a job, and calls the SMS adapter to talk to the provider.

## UI layout

Use `ui/src/app/router.tsx` for the React Router route table and `ui/src/app/layouts/` for shared page layouts. Put URL-level pages in `ui/src/routes/`, feature-specific forms and API calls in `ui/src/features/`, and reusable controls and HTTP helpers in `ui/src/shared/`. Build the UI after the API can be exercised independently. [React Router nested routes](https://reactrouter.com/start/data/routing#nested-routes)

Public pages include `/`, `/login`, and `/consent/:token`. The signed-in layout at `/app` hosts the dashboard, messages, recipients, and billing pages. It can direct a signed-out visitor to `/login`. Axum checks the access JWT and account ownership on protected API requests.

## HTTP and authentication

| Entry point | Contract |
| --- | --- |
| `GET /health` | Runs `SELECT 1` with a two-second timeout. Returns `200 OK` when PostgreSQL answers or `503 Service Unavailable` on a query failure or timeout. |
| `POST /api/auth/google/start`, `GET /api/auth/google/callback` | Bind the browser key to an OpenID Connect attempt and establish a session after the verified callback. |
| `POST /api/auth/refresh` | Verify the browser proof and rotate the access and refresh cookies. |
| `POST /api/auth/logout` | Verify the browser proof, delete the session, and clear both cookies. |
| `GET /api/me` | Optional current-account endpoint for the UI. |
| `GET /api/recipients`, `POST /api/recipients` | List account recipients or add a recipient and create a consent invitation. |
| `GET /api/consent/{token}`, `POST /api/consent/{token}` | Load invitation details for the public consent page and record a recipient's decision. |
| `GET /api/messages`, `POST /api/messages` | List account messages or reserve a credit and schedule a message. |
| `PATCH /api/messages/{id}`, `POST /api/messages/{id}/cancel` | Edit a queued message or cancel it and release its credit. |
| `GET /api/billing/bundles`, `GET /api/billing/balance` | List available bundles and read the account's credit balance. |
| `GET /api/billing/orders`, `POST /api/billing/orders`, `GET /api/billing/orders/{id}` | List orders, start hosted checkout, and read the status of an account's order. |
| `POST /api/billing/return` | Receive the gateway's browser return and redirect to the React order page. |
| `POST /api/webhooks/payments`, `POST /api/webhooks/sms` | Verify payment notifications and authenticate SMS provider events. |

### Authentication flow

This is the planned design for Payra. Google establishes the user's identity. Payra signs and issues its own access and refresh JWTs with separate signing keys and token types. The browser signs a fresh proof when it registers its key, refreshes, or logs out. V1 uses Web Crypto for browser proofs. Device Bound Session Credentials (DBSC) is deferred.

| Where | What is stored |
| --- | --- |
| React origin's IndexedDB | The browser's Web Crypto key pair. The private key is non-exportable and Web Crypto uses it to sign proofs. |
| Browser cookie jar | The Payra-issued access JWT and refresh token in host-only, `Secure`, `HttpOnly`, `SameSite=Lax` cookies. Use `Path=/api` for access and `Path=/api/auth` for refresh so both refresh and logout receive the refresh cookie. |
| PostgreSQL | One durable `auth_sessions` row per login with the account ID, browser public key, refresh counter, and expiry dates. Login attempts and used proof IDs have short retention periods. |

#### Sign in with Google

1. React creates a P-256 key pair with Web Crypto and saves it in IndexedDB. It starts sign-in with the public key and proof that it can sign with the matching private key.
2. Axum verifies that proof and creates a short-lived login attempt. The attempt ties the public key to the OAuth state, nonce, and PKCE verifier. A temporary `HttpOnly` cookie links the attempt to the browser making the callback. React navigates to the Google authorization URL returned by Axum.
3. On callback, Axum validates the state and browser cookie, consumes the attempt once, and exchanges the authorization code using PKCE. Use a maintained OpenID Connect library to verify the Google ID token's signature, issuer, audience, expiry, and nonce. Google's stable subject identifier maps to the local account.
4. Axum saves the registered public key in a new session row at counter `1`. It signs an access JWT and a refresh token and returns them as cookies before redirecting to React. Each login gets its own session, including logins on other devices.

```mermaid
sequenceDiagram
    participant Browser as React and browser
    participant API as Axum
    participant Google
    participant DB as PostgreSQL
    Browser->>Browser: Create key pair and save to IndexedDB
    Browser->>API: POST /api/auth/google/start with public key and proof
    API->>API: Verify browser proof
    API->>DB: Save short-lived login attempt with public key
    API-->>Browser: Login-attempt cookie and Google URL
    Browser->>Google: Sign in
    Google-->>Browser: Redirect with authorization code and state
    Browser->>API: GET /api/auth/google/callback
    API->>DB: Validate and consume login attempt
    API->>Google: Exchange code with PKCE verifier
    Google-->>API: Signed ID token
    API->>DB: Find or create account and insert session at counter 1
    API-->>Browser: Set access and refresh cookies and redirect to app
```

[Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect), [OAuth security best current practice](https://www.rfc-editor.org/rfc/rfc9700.html), [Web Crypto key storage](https://www.w3.org/TR/webcrypto/#key-storage)

#### Make an API request

Payra signs the access JWT when it issues it. The access JWT carries the local account ID and session ID. Axum verifies the JWT signature, type, issuer, audience, and expiration locally. The browser sends the access cookie on protected API calls. Browser signatures are reserved for authentication endpoints in v1.

Derive `account_id` from the validated access JWT for every customer query. For example, a cancellation matches both the URL's message ID and the authenticated account ID. Composite foreign keys protect ownership relationships. For sensitive actions such as reserving credits, also check that the session exists and is active inside the business transaction. A refreshable `401` signals missing or expired access credentials before a handler performs any side effects. A `403` or `404` follows normal error handling. [JWT validation guidance](https://www.rfc-editor.org/rfc/rfc8725.html), [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)

```mermaid
sequenceDiagram
    participant Browser
    participant API as Axum
    participant DB as PostgreSQL
    Browser->>API: API request with access cookie
    API->>API: Verify Payra-signed access JWT
    opt Sensitive write
        API->>DB: Check and lock active session inside transaction
    end
    API->>DB: Run account-scoped operation
    API-->>Browser: Return result
```

#### Refresh across browser tabs

When an API call gets a refreshable `401`, use `navigator.locks.request("payra-refresh", ...)` to take an exclusive lock shared by tabs on the React origin. After acquiring it, retry the original API request directly. A successful retry means the shared access cookie is already usable. If it still gets a refreshable `401`, hold the lock while Axum refreshes and the browser applies the new cookies. Then release the lock and retry the original request once. If the tab closes, the next tab can take the lock and recheck by retrying its own original request. On a definitive refresh rejection, send the user back to sign-in.

The refresh JWT contains the session ID, counter, and expiry. Its lifetime is capped by the earlier session deadline. The browser attaches it as an `HttpOnly` cookie and sends a separate proof signed with its private key. The proof covers the HTTP method, the refresh URL, a timestamp, and a unique request ID. Axum validates the refresh JWT and locks its session row. It checks both session deadlines, verifies the proof with the saved public key, checks that the proof matches the request and is recent, and rejects reused proof IDs before comparing the refresh counter.

- If the token counter matches the session counter, record the proof ID, advance the counter, and extend the idle deadline in one transaction. Commit before returning the new access and refresh cookies.
- If a correctly signed refresh token with a valid fresh browser proof has a lower counter, delete that session and commit before returning `401`. A wrong key, a repeated proof ID, or a future counter fails before any revocation.

The following example starts with access cookie `A1` expired and refresh cookie `R1` at counter `1`.

```mermaid
sequenceDiagram
    participant A as Tab A
    participant B as Tab B
    participant Lock as Web Locks
    participant API as Axum
    participant DB as PostgreSQL
    A->>API: Original request with expired access
    API-->>A: 401
    B->>API: Original request with expired access
    API-->>B: 401
    A->>Lock: Request exclusive refresh lock
    Lock-->>A: Lock granted
    B->>Lock: Request same lock and wait
    A->>API: Retry original request
    API-->>A: Still 401
    A->>API: POST /api/auth/refresh with R1 and signed proof
    API->>DB: Lock session and load saved key and counter
    API->>API: Verify browser proof and compare counter
    API->>DB: Record proof ID, advance counter to 2, and commit
    API-->>A: Set shared cookies A2 and R2
    A->>Lock: Release after browser applies cookies
    Lock-->>B: Lock granted
    B->>API: Retry original request with A2
    API-->>B: Success
    B->>Lock: Release lock
    A->>API: Retry original request with A2
    API-->>A: Success
```

[Web Locks](https://www.w3.org/TR/web-locks/), [Proof verification and replay considerations](https://www.rfc-editor.org/rfc/rfc9449.html#section-11.1)

#### Expiry and logout

Initial lifetime targets are a **10-minute access token**, **30 days of refresh inactivity**, and a **90-day absolute session limit**. A successful refresh moves the idle deadline forward up to the absolute limit set at sign-in. Axum enforces both deadlines even before cleanup runs. The worker deletes expired sessions daily and removes expired proof IDs and abandoned login attempts. Active sessions rotate when the browser requests a refresh.

Logout uses `POST /api/auth/logout` with the refresh cookie and a fresh proof signed for that route. Its cookie path lets the browser send the refresh cookie even after the access JWT expires. Use the same Web Lock to order logout after any in-flight refresh. Axum verifies the signed refresh token and browser proof, deletes the identified session, commits, and clears both cookies using their original paths. All refresh tokens for that session stop working. React returns to sign-in when a later refresh gets `401`.

```mermaid
sequenceDiagram
    participant Browser as React and browser
    participant API as Axum
    participant DB as PostgreSQL
    Browser->>Browser: Acquire the refresh lock and sign logout proof
    Browser->>API: POST /api/auth/logout with refresh cookie and proof
    API->>API: Validate refresh JWT
    API->>DB: Load registered browser key
    API->>API: Verify logout proof
    API->>DB: Delete session
    DB-->>API: Commit
    API-->>Browser: Clear both cookies
    Browser->>Browser: Release lock and show sign-in
```

#### Browser and session risks

- Enforce the expected `Origin` on cookie-authenticated mutations. For a separately hosted UI, use HTTPS origins on the same site, allow credentials only for the configured React origin, and use `credentials: "include"` in cross-origin API calls. Google and provider callbacks use their own verification flows. [OWASP CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- **High impact.** Malicious JavaScript running inside Payra can ask Web Crypto to sign and can act through the signed-in browser. Render SMS content and other customer input as text. Malware with sufficient device access may be able to recover a software-protected key. [React raw HTML guidance](https://react.dev/reference/react-dom/components/common#dangerously-setting-the-inner-html), [Web Crypto security considerations](https://www.w3.org/TR/webcrypto/#security-considerations)
- **Medium impact, potentially high for paid actions.** A copied access JWT can authorize ordinary requests until it expires. It can also authorize sensitive actions while its session is active. Deleting the session blocks actions that check it live.
- **Low impact.** If the refresh transaction commits and the response is lost, the browser can retain the old refresh cookie. The next refresh with that cookie and a fresh proof revokes the login under the strict counter rule. The user can sign in again.

A consent link uses a random, expiring token tied to one recipient, with only its digest stored in the database. Opening the link loads the consent page. Record the decision only after a POST so a link preview cannot opt someone in.

## PostgreSQL model

| Table | Key fields and constraints |
| --- | --- |
| `accounts` | Internal ID, unique Google subject, display name, email, preferred time zone, sending hold status. |
| `auth_sessions` | Session ID, account ID, browser public key, refresh counter, idle expiry, and absolute expiry. One row per login. |
| `auth_login_attempts` | Short-lived browser-bound OAuth state, nonce, PKCE verifier, and pending public key. Consume each attempt once. |
| `auth_proof_uses` | Session ID, proof ID, and expiry. A unique `(session_id, proof_id)` prevents reuse during the proof validity window. |
| `recipients` | Account ID, ID, normalized phone number, active flag, consent status, consent timestamp and evidence reference, invitation digest and expiry. Unique `(account_id, phone_e164)` and `(account_id, id)`. |
| `sms_suppressions` | Sending identity, normalized phone number, opt-out timestamp. Unique `(sender_key, phone_e164)`. |
| `scheduled_messages` | Account ID, recipient ID, rendered body, time zone, `send_at`, `expires_at`, `next_attempt_at`, submission and delivery status, claim token and lease deadline, attempt count, provider message ID. Composite FK `(account_id, recipient_id)` and unique submission key within the account. |
| `sms_delivery_events` | Verified event key, provider message ID, status, timestamps. Keep unmatched receipts until the dispatch result links them to a message. |
| `bundles` | Bundle code, label, positive credit quantity, positive price in BDT minor units, active flag. |
| `payment_orders` | Account ID, bundle code, price and credit snapshots, unique merchant transaction ID, gateway transaction reference, status. A confirmed gateway transaction can settle only one order. |
| `credit_balances` | Account ID, available and reserved credits as nonnegative integers. One row per account. |
| `credit_ledger` | Account ID, available and reserved deltas, event kind, linked order or message ID, timestamp, unique event key. Enforce one purchase grant per order and one settlement per message. |

Use `timestamptz` for scheduled instants, retry deadlines, and event times. Index scoped history by account and time. Use a partial index on `(next_attempt_at, id)` for messages in the scheduled state, plus an index for expired sending leases. Compare gross checkout amounts as integers in minor units or as exact decimals when parsing gateway values. [PostgreSQL timestamp types](https://www.postgresql.org/docs/current/datatype-datetime.html)

## Transaction and retry boundaries

**Schedule or cancel.** Check and lock the active session row before locking the account's balance row. For creation, validate eligibility and available credit, insert the message, and write the reservation and updated balance in one transaction. A form submission key unique within the account lets a repeated request return the original schedule. For cancellation, conditionally transition a scoped scheduled row and release only if that transition succeeds. Use balance-row-then-message-row lock order when both are needed. [PostgreSQL row locks](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS)

**Apply a payment.** Validate with the provider outside the database transaction. Inside the transaction, lock the account balance, confirm the saved order still matches, and write the order update, ledger grant, and balance together. A unique purchase event makes replay safe. Keep cancellation and success redirects informational. A reconciliation job checks old pending orders through the gateway so notification delivery is not the only recovery path. [SSLCOMMERZ order validation and transaction lookup](https://developer.sslcommerz.com/doc/v4/)

**Claim and submit.** In a short transaction, select one due scheduled row with `FOR UPDATE SKIP LOCKED`, then mark it sending with a fresh claim token and lease deadline. Commit before contacting the provider. Check consent, suppression, account holds, and expiry before the call. Enforce account-wide and global send limits with atomic counter updates before dispatch. Require enough lease time for the bounded provider call and record the result only for that claim. A confirmed temporary rejection can reschedule the same job with bounded backoff. Expiry releases the credit when non-acceptance is known. [PostgreSQL queue-style row locking](https://www.postgresql.org/docs/current/sql-select.html)

**Recover and reconcile.** Expire overdue queued jobs and release their reservations. Move interrupted sending jobs with expired leases to unknown and retain their reservations. Resolve an unknown job from provider evidence, including a late result from the same claim if it is still current. When a provider offers a documented idempotency key for message creation, reuse the job's stable key. Store verified delivery receipts before acknowledging them, then apply them once their provider message ID is known. Duplicate or out-of-order receipts must preserve the recorded terminal status.

## Docker and VPS

- In development, run Axum locally and configure Vite to proxy `/api` to it. In production, build React in the Caddy Dockerfile and copy the output into the Caddy image. Caddy forwards `/api/*` to Axum with the path intact. It serves static assets and uses `index.html` for React page URLs so a direct visit to a route like `/app/messages` works. This deployment gives the React app and API one public origin. See the [browser request rules](#browser-and-session-risks) for a separately hosted UI. [Vite dev proxy](https://vite.dev/config/server-options.html#server-proxy), [Caddy SPA routing](https://caddyserver.com/docs/caddyfile/patterns#single-page-apps-spas)
- Run `caddy`, `web`, `worker`, and `db` in Compose. Run migrations as a one-shot command before starting the two Rust services. Use a PostgreSQL health check and service DNS at `db:5432`. Publish the HTTPS entry point through Caddy and keep the other services on the Compose network. [Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/), [Compose networking](https://docs.docker.com/compose/how-tos/networking/), [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https)
- On the VPS, configure the Rust services with `env_file: ./server/.env` in the root Compose file. Use `db:5432` in the production database URL and set `BIND_ADDR=0.0.0.0:3000` so Caddy can reach Axum. When the Rust image uses `./server` as its build context, exclude `.env` with `server/.dockerignore`. [Compose environment files](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/#use-the-env_file-attribute), [Docker build context](https://docs.docker.com/build/concepts/context/#dockerignore-files)
- Use a multi-stage image with a non-root runtime user. Inject Google, payment, SMS, and database credentials at runtime. Keep the database and Caddy data on persistent volumes. Back up PostgreSQL off the VPS and test a restore. [Docker multi-stage builds](https://docs.docker.com/build/building/multi-stage/), [PostgreSQL backups](https://www.postgresql.org/docs/current/app-pgdump.html)
- Log internal IDs and state transitions. Track the age of the oldest due job, unknown sends, overdue pending payments, and failed submissions. Keep message bodies, full phone numbers, tokens, and gateway secrets out of logs, including token-bearing URLs in proxy logs. Use bounded provider timeouts and graceful shutdown so the worker stops taking new jobs before exiting.

## Build milestones

| Milestone | Exercise | Done when |
| --- | --- | --- |
| 1. Axum and Google | Use the existing Axum modules and PostgreSQL pool. Build Google sign-in, browser key registration, JWT authentication, refresh, and logout. | `GET /health` reports database reachability, Google users have separate accounts, and two tabs share one refresh. |
| 2. One scheduled text | Write the first scheduling queries in `psql`, then call them through SQLx. Run `worker --once` with a fixed clock and fake sender. | A queued test message reaches the fake sender and appears in account-scoped history. |
| 3. Credits and concurrency | Seed test credits. Implement the ledger and balance transaction, cancellation, and worker claims. Run two concurrent requests for the last credit. | One reservation wins, cancellation releases it once, and two workers cannot both claim a job. |
| 4. Wallet checkout | Connect sandbox checkout. Exercise successful, failed, mismatched, and duplicate notifications. | One verified order creates one credit grant. The dashboard can show an order while confirmation is pending. |
| 5. Live SMS and VPS | Add verified provider callbacks, consent enforcement, send limits, and the production Compose deployment. Test cancellation, opt-out, provider timeout, and restart recovery. | A real purchase and send work on the VPS and a backup can be restored into a separate database. |
