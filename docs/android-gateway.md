# Android SMS gateway

Payra owns the SMS schedule, queue, and history. A dedicated Android phone claims one job, sends it through the selected SIM, and reports the outcome. Part 5 has been tested with the local Bun mock. The Rust server integration is next.

## Phone architecture

Kotlin app code is under `android-gateway/app/src/main/kotlin/com/payra/gateway/`.

| Package | Responsibility |
| --- | --- |
| `ui/` | Compose setup, settings, and dashboard. The operator grants phone and SMS permissions, selects a SIM, enters the server URL, then starts the gateway. The dashboard shows Idle, Waiting to send, Sending, or Result pending. |
| `runtime/` | `GatewayService` owns SIM monitoring, the WebSocket, claiming, sending, and reporting. It runs with a foreground notification while the screen is locked. Start and Pause persist across restarts. Boot recovery runs after the first unlock when the gateway is enabled and Android allows it. |
| `network/` | `GatewaySocket` receives work notices and reconnects as the default network changes. `GatewayApi` claims jobs and uploads results over HTTP(S). |
| `sms/` | `SmsSender` splits text into SMS parts and sends through the configured subscription. `SmsResultReceiver` records Android send callbacks for each part. |
| `storage/` | DataStore saves SIM, URL, heartbeat, part limit, appearance, and run preference. Room saves the current job, part callbacks, and the pending report. Commit the generated schemas in `android-gateway/app/schemas/` with database migrations. |

## Server contract

| Request | Contract |
| --- | --- |
| WebSocket `/gateway` | Server sends `{"type":"work_available"}`. When the SIM and socket are ready, the phone checks for work on connection, on a notice, and after acknowledging the previous job. |
| `POST /jobs/claim` | Return `200` with `{id, attempt, to, text}`, or `204` for an empty queue. Use a stable job UUID and start attempts at `1`. |
| `POST /jobs/{id}/result` | Phone sends `{attempt, status, partCount, errorCode, retryable}`. Persist the outcome before returning `200` with `{id, attempt}`. Return the same acknowledgment for an identical replay of the same job and attempt. |

The phone saves a claim before sending and waits for acknowledgment before claiming again. A saved job can send without internet while the SIM is in service. Once Android records the outcome, the app clears the recipient and text from its job record. It keeps the pending report across restarts and retries the same report after a transient error or when the network returns. A matching acknowledgment clears the report and its part records.

Result statuses are `sent`, `failed`, `partial`, `unknown`, and `too_long`. `sent` means Android reported send success for every SMS part. Recipient delivery would require a separate receipt. Only a confirmed temporary failure with zero successful parts is marked retryable. The server decides whether to schedule another attempt.

## Local testing and release work

Run `bun payra-gateway-test/server.js` from the repo root. Point the Android debug build at the PC’s LAN URL on port `8765`. The mock commands are `queue`, `drop-next-ack`, and `simulate-failure`. Eligible mock retries use the same UUID with attempt `2` and go to the back of the SQLite queue. Let any pending mock job reach Idle before changing the server URL.

The release build requires HTTPS. Remaining work includes the real Payra endpoints, per-device authentication for both HTTP and WebSocket, and recovery when the server permanently rejects a result. Before unattended deployment, resolve release lint, configure APK signing, exclude temporary SMS data from backup, and verify reboot and network failover on the target phones.
