# Android SMS gateway

Payra owns the SMS schedule, queue, and history. Android claims one job at a time, sends through the selected SIM, and reports the outcome to Payra.

## Phone architecture

Kotlin app code is under `android-gateway/app/src/main/kotlin/com/payra/gateway/`.

| Package | Responsibility |
| --- | --- |
| `ui/` | Compose setup, settings, and dashboard. The operator grants phone and SMS permissions, selects a SIM, enters the server URL and gateway key, then starts the gateway. |
| `runtime/` | `GatewayService` owns SIM monitoring, the WebSocket, claiming, sending, and reporting. It runs with a foreground notification while the screen is locked. Start and Pause persist across restarts. Boot recovery runs after the first unlock when the gateway is enabled and Android allows it. |
| `network/` | `GatewaySocket` receives work notices and reconnects as the default network changes. `GatewayApi` claims jobs and uploads results over HTTP(S). |
| `sms/` | `SmsSender` splits text into SMS parts and sends through the configured subscription. `SmsResultReceiver` records Android send callbacks for each part. |
| `storage/` | DataStore saves server settings and the encrypted gateway key. Android Keystore protects the encryption key. Room saves the current job, part callbacks, and pending report for the sending flow. Commit the generated schemas in `android-gateway/app/schemas/` with database migrations. |

## Server contract

| Request | Contract |
| --- | --- |
| WebSocket `/api/sms/gateway/ws` | Receive `{"type":"work_available"}` and check for a claim when the SIM is ready. |
| `POST /api/sms/gateway/jobs/claim` | Return `200` with `{id, attempt, to, text}`, or `204` when no work is available. A repeated claim returns the outstanding assignment with the same job UUID and attempt number. |
| `POST /api/sms/gateway/jobs/{id}/result` | Phone sends `{attempt, status}`. Payra saves the outcome before returning `200` with `{id, attempt}`. A matching replay receives the same acknowledgment. |

The phone saves a claim before sending and waits for acknowledgment before claiming again. A saved job can send without internet while the SIM is in service. Once Android records the outcome, the app clears the recipient and text from its job record. It keeps the pending report across restarts and retries the same report after a transient error or when the network returns. A matching acknowledgment clears the report and its part records.

The phone uses the same bearer key on all three routes. `sent` means Android reported send success for every SMS part. Recipient delivery requires a separate receipt. The other outcomes are `failed`, `partial`, and `unknown`. Payra owns the retry policy, including when the outcome is unknown.

## Local testing and release work

Set `GATEWAY_API_KEY_SHA256` on Payra to the SHA-256 hex digest of the 64-character key. Run Payra and the SMS scheduler, paste the original key and server URL into Android, and start the gateway. Schedule an SMS to a number you control. Watch the claim, send outcome, and ACK with `adb logcat -s GatewayClaims:I GatewaySms:I GatewayReports:I`.

The release build requires HTTPS. A permanently rejected report stays on the phone and appears on the dashboard. After correcting the cause, restart the gateway to retry the saved report. Before unattended deployment, resolve release lint, configure APK signing, exclude temporary SMS data from backup, and verify reboot and network failover on the target phones.
