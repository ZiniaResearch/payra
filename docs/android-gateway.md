# Android SMS gateway

Payra owns the SMS schedule, queue, and history. The Android gateway connects to the authenticated Payra WebSocket and logs work notices. Claiming, sending, and reporting were tested against the Bun mock and will be wired to the Payra HTTP endpoints separately.

## Phone architecture

Kotlin app code is under `android-gateway/app/src/main/kotlin/com/payra/gateway/`.

| Package | Responsibility |
| --- | --- |
| `ui/` | Compose setup, settings, and dashboard. The operator grants phone and SMS permissions, selects a SIM, enters the server URL and gateway key, then starts the gateway. |
| `runtime/` | `GatewayService` owns SIM monitoring and the WebSocket. It runs with a foreground notification while the screen is locked. Start and Pause persist across restarts. Boot recovery runs after the first unlock when the gateway is enabled and Android allows it. |
| `network/` | `GatewaySocket` receives work notices and reconnects as the default network changes. `GatewayApi` claims jobs and uploads results over HTTP(S). |
| `sms/` | `SmsSender` splits text into SMS parts and sends through the configured subscription. `SmsResultReceiver` records Android send callbacks for each part. |
| `storage/` | DataStore saves server settings and the encrypted gateway key. Android Keystore protects the encryption key. Room saves the current job, part callbacks, and pending report for the sending flow. Commit the generated schemas in `android-gateway/app/schemas/` with database migrations. |

## Server contract

| Request | Contract |
| --- | --- |
| WebSocket `/api/sms/gateway/ws` | Android sends `Authorization: Bearer <key>` and logs `{"type":"work_available"}` when received. |
| Planned `POST /api/sms/gateway/jobs/claim` | Return `200` with `{id, attempt, to, text}`, or `204` for an empty queue. Use a stable job UUID and start attempts at `1`. |
| Planned `POST /api/sms/gateway/jobs/{id}/result` | Phone sends `{attempt, status, partCount, errorCode, retryable}`. Persist the outcome before returning `200` with `{id, attempt}`. Return the same acknowledgment for an identical replay of the same job and attempt. |

The phone saves a claim before sending and waits for acknowledgment before claiming again. A saved job can send without internet while the SIM is in service. Once Android records the outcome, the app clears the recipient and text from its job record. It keeps the pending report across restarts and retries the same report after a transient error or when the network returns. A matching acknowledgment clears the report and its part records.

Result statuses are `sent`, `failed`, `partial`, `unknown`, and `too_long`. `sent` means Android reported send success for every SMS part. Recipient delivery would require a separate receipt. Only a confirmed temporary failure with zero successful parts is marked retryable. The server decides whether to schedule another attempt.

## Local testing and release work

Set `GATEWAY_API_KEY_SHA256` on Payra to the SHA-256 hex digest of the 64-character key. Paste the original key and Payra’s base URL into Android’s Server connection settings. Start the gateway and run `adb logcat -s GatewaySocket:I` to see `WebSocket connected` and `Received work_available` when the scheduler finds due jobs.

The release build requires HTTPS. Remaining work includes authenticated claim and result endpoints and recovery when the server permanently rejects a result. Before unattended deployment, resolve release lint, configure APK signing, exclude temporary SMS data from backup, and verify reboot and network failover on the target phones.
