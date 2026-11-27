# SMS scheduling SaaS system design

Payra's desired product lets customers sign in with Google, buy a bundle through bKash or Nagad, and schedule a text. The customer chooses the recipient, writes the message, and picks a local date, time, and time zone. The planned React pages show upcoming texts, available/reserved credits, payment history, and delivery status from the Axum API.

See the [architecture](architecture.md) for the Axum modules, SQL transactions, deployment, and build milestones.

## Current implementation boundary

Google sign-in, browser-key-bound sessions, signed refresh/logout, and the profile modal are implemented. The profile supports name and preset-cover edits, Garage-backed avatar uploads when configured, and account deletion authorized by a browser-key proof. Uploaded avatars are removed on a best-effort basis after replacement or account deletion. The browser has a full BoardUI sidebar with theme/account controls. Recipients, scheduling, credits, payments, other product pages, a product worker, and production deployment remain planned. See [current state and merge checks](architecture.md#verification-and-merge-checklist) for outstanding live verification.

## Frontend navigation and loading

The sidebar order is **Schedule SMS** (`/messages/new`), **Messages** (`/messages`), **Analytics** (`/analytics`), and **Buy Credits** (`/billing`). All four routes currently have empty shells; scheduling, message statuses/data, analytics data, and purchases remain planned. `/profile` and `/recipients` are also planned. There is no dashboard URL prefix. The pathless `_protected` layout shares the sidebar, account menu, theme control, and logout. Header integration/mobile navigation are explicitly deferred. Route files arrange components; feature modules own UI, reads in `queries.ts`, writes in `mutations.ts`, and local loading/error states. The existing auth `api.ts` remains for Google login; it is not a required layer for other features. See [frontend ownership](architecture.md#ui-layout).

The client-rendered frame and selected page appear immediately, without an authentication loader or local-storage-presence gate. The account area independently loads `GET /api/profile` with a skeleton, email, or unavailable/retry state. The old identity-demo loader/helper/backend endpoint are removed. Future product sections fetch concurrently and become visible individually; one slow section must not hold the entire page behind a combined result.

Missing/expired access uses the shared lock/recheck/signed-refresh/retry flow. Terminal auth failures use one shared Query/Mutation handler for cache cleanup/login navigation. Network/server failures stay local and retryable, while Query can pause work offline; they do not force logout. Backend JWT verification—not layout grouping or IndexedDB—is the security boundary. The current sidebar exposes no fake users/counts or persisted settings; workspace/settings/support actions are not product functionality yet.

## Planned first release

| Area | Behavior |
| --- | --- |
| Accounts | The first Google sign-in creates a personal account. Each account owns its recipients, schedules, orders, and credit balance. |
| Scheduling | One recipient, one short SMS, and one send time per schedule. Customers can edit or cancel queued messages. |
| Bundles | Small, Mid, and Large are one-time purchases. Each adds a fixed number of credits to the same balance. Customers can buy another full bundle at any balance. |
| Payments | Customers pay in BDT through bKash or Nagad on hosted checkout. A verified payment grants credits. |
| Delivery | Analytics/message pages distinguish provider acceptance from a delivery receipt and show when a message needs attention. |

The example pack sizes are **25, 100, and 500 credits**. Quantities and prices are configurable. Confirm SMS costs and gateway fees before setting live prices. Credits from multiple purchases share one balance and remain available until used or refunded.

One credit covers one SMS segment, including any sender identification or opt-out text added to the message. The editor previews the length and the server validates the final encoded body. Bangla, emoji, and some punctuation can change how much text fits in one segment. Validate against the chosen SMS provider's encoding behavior before launch. [SMS encoding and segmentation](https://www.twilio.com/docs/glossary/what-sms-character-limit)

A birthday greeting and an appointment reminder use the same scheduler. Yearly repetition is a later milestone. Each occurrence will create its own scheduled message and reserve a credit when it is queued.

## Account sessions

Customers click **Continue with Google** and choose an account in Google's popup. Payra exchanges/verifies the authorization code server-side, creates/reuses the account, binds a new session to the browser public key, and opens **Schedule SMS** (`/messages/new`) after saving the local session/key metadata. The sidebar then fetches the account's real email independently.

Access lasts 10 minutes; refresh has a 30-day inactivity deadline capped by a 90-day absolute deadline. Tabs on the same React origin serialize login/refresh/logout through Web Locks and share cookies/IndexedDB. Each browser/device signs in separately. Logout ends refresh for one login and synchronizes mounted account views via session-ended notices. Already-issued copied access JWTs may remain usable until expiry. Strict refresh reuse detection can require a new login after a lost committed response; there is no replay grace. See [authentication](auth.md) for contracts and recovery.

```mermaid
flowchart LR
    Login["Click Continue with Google"] --> Google["Choose a Google account"]
    Google --> Active["Use Payra"]
    Active -->|Access expires| Refresh["One tab refreshes"]
    Refresh -->|Refresh succeeds| Active
    Refresh -->|Session expired or revoked| Login
    Active -->|Log out| Login
```

## Scheduling rules

The remaining product rules in this document are the agreed target, not currently implemented APIs, jobs, or database tables.

- Default the time zone selector to the customer's saved preference, initially `Asia/Dhaka`. Store the chosen IANA time zone alongside the resolved `send_at` instant. If a local time occurs twice, ask the customer to pick an offset. If it does not occur, ask for another time. [PostgreSQL date and time types](https://www.postgresql.org/docs/current/datatype-datetime.html)
- Commit each new schedule, edit, or cancellation before waking the worker. The worker reads the earliest queued attempt time through an index and keeps its timer in memory. It reads PostgreSQL again on startup and sends a work notice when a job becomes due.
- Timer changes and work notices leave job status alone. The Android gateway's claim atomically assigns an attempt. Payra records the outcome before acknowledging it. A confirmed temporary failure on attempt 1 can queue attempt 2 within the send window in the same transaction.
- Store a send-by instant on each job so a restart can pick up queued jobs inside their window and expire older ones. The send window is six hours from the scheduled time.
- Queue only for an active, opted-in recipient. The account owner shares an invitation link through an existing channel. Record consent for that account and phone number through a flow accepted by the SMS provider. Replacing a phone number deactivates the old recipient entry and creates a new entry that needs consent. Apply provider opt-outs to every affected recipient and recheck consent and suppression before dispatch. [Example SMS provider messaging policy](https://www.twilio.com/en-us/legal/messaging-policy)
- A queued text can be edited or cancelled until the gateway claims it. An edit revalidates the final body, consent, and time. It retains the existing reservation because each scheduled text costs one credit. Cancellation releases that reservation.

## Credit lifecycle

The credit ledger is an append-only record of changes. A balance row holds the current available and reserved totals. Update both inside the same PostgreSQL transaction. The balance must stay nonnegative and match the sum of ledger changes.

| Event | Available change | Reserved change |
| --- | ---: | ---: |
| Verified purchase of a bundle of `n` credits | +n | 0 |
| Queue one message | −1 | +1 |
| Provider accepts the message | 0 | −1 |
| Queued message cancelled, expired, or definitively failed before acceptance | +1 | −1 |
| Provider outcome is unknown | 0 | 0 |

For example, after buying 25 credits and scheduling three texts, a customer has 22 available and three reserved. Cancelling one gives them 23 available and two reserved. Once the provider accepts a text, that reservation is consumed even if the carrier later reports it undelivered. Show this rule alongside the balance and send history.

Each purchase grants credits once. Each message gets one reservation and one eventual settlement, either consumption or release. Retries retain the original reservation. An unknown outcome stays reserved until the provider or an operator can establish whether the SMS was accepted. Link each verified refund or reversal to its original order and apply the corresponding ledger adjustment once. If the available balance is insufficient, keep the adjustment pending and pause scheduling and sending for that account until an operator resolves it.

## Payment lifecycle

1. Create a pending order with the account, bundle code, credit count, price in BDT minor units, and a unique merchant transaction ID. Snapshot the amount and quantity so catalog edits do not change an existing order.
2. Create a hosted checkout session and redirect the customer. After checkout, Axum directs the customer back to the React order page. The page reads the order status through the account-scoped API.
3. The backend validates the gateway notification or checks the order through the gateway API. It matches the transaction ID, gross order amount, currency, and accepted status. A gateway risk flag keeps the order under review.
4. In one database transaction, finalize the verified order, append the credit grant, and update the balance. Duplicate or concurrent confirmations resolve to the same purchase. Reconcile older pending orders if a notification is delayed or missed.

SSLCOMMERZ is the sandbox candidate. Confirm that both bKash and Nagad are enabled for the live merchant account. Keep the SMS provider open until sender approval, Bangladesh delivery support, opt-out handling, and message-status reporting are verified. [SSLCOMMERZ payment channels](https://sslcommerz.com/pricing/), [SSLCOMMERZ integration and validation](https://developer.sslcommerz.com/doc/v4/)

## Message lifecycle

```mermaid
stateDiagram-v2
    [*] --> Scheduled: reserve one credit
    Scheduled --> Sending: Android gateway claims
    Scheduled --> Cancelled: release credit
    Scheduled --> Expired: release credit
    Sending --> Scheduled: confirmed retryable rejection
    Sending --> Cancelled: consent withdrawn before submission
    Sending --> Expired: window closed before submission
    Sending --> Submitted: accepted and credit consumed
    Sending --> Failed: confirmed rejection and credit released
    Sending --> Unknown: timeout or interrupted attempt
    Unknown --> Submitted: acceptance confirmed and credit consumed
    Unknown --> Failed: non-acceptance confirmed and credit released
```

Keep delivery status separate from submission state. After submission, a provider receipt can mark the SMS delivered or undelivered. A retry is eligible only after a definite temporary rejection and only within the send window. Reconcile unknown attempts before attempting another provider call. A database transaction can prevent two workers claiming the same job, but an SMS provider can accept a request before the worker records the result. [Example SMS delivery lifecycle](https://www.twilio.com/docs/messaging/guides/outbound-message-status-in-status-callbacks)

## Launch checks

These are release acceptance criteria, not completed verification. See [auth/profile manual checks](auth.md#manual-verification); product checks become runnable as their features are built.

- Two customers can sign in using the Google button, and each can access only their own recipients, orders, balances, and messages. A repeat sign-in with the same Google account reuses the existing Payra account.
- Two tabs with expired access share one refresh. A refresh cookie sent without the registered browser key fails. Proofs cover method, exact URL, timestamp, session ID, and the cookie token's SHA-256 hash. The counter stays in the signed refresh JWT/database. A used refresh token with a valid recent matching proof revokes its session, including an exact duplicate of an accepted request. An old proof paired with a newer token fails without revocation. See [manual checks](auth.md#manual-verification).
- Logout works after access expiry and blocks subsequent refreshes for that login. Credit reservations check for an active session.
- Both wallets complete a real test checkout after sandbox testing. Duplicate confirmations grant one bundle. Cancelled payments and mismatched amounts grant zero credits.
- With one credit left, two concurrent scheduling requests produce one scheduled text. Cancellation restores the credit once.
- A test SMS reaches an opted-in phone. An opted-out recipient results in zero provider submissions. Accounts and the application have send limits.
- Restarting the VPS preserves balances, schedules, and history. A queued message can resume within its send window. An interrupted provider attempt appears as unknown for reconciliation.
