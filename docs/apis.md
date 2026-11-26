# Payra API reference

## Implemented Payra server routes

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | Check PostgreSQL connectivity. Returns `200` when reachable and `503` otherwise. |
| POST | `/api/auth/google` | Sign in with a Google authorization code and a browser public key. Sets session cookies and returns session metadata. |
| POST | `/api/auth/refresh` | Verify the signed browser proof, rotate session cookies, and return updated session metadata. |
| POST | `/api/auth/logout` | Verify the signed browser proof, end the current login, and clear its cookies. |
| GET | `/api/profile` | Return the signed-in account’s profile. |
| PATCH | `/api/profile` | Update the account name, cover preset, or both. Returns the saved profile. |
| GET | `/api/profile/covers` | List the available cover presets. |
| PUT | `/api/profile/avatar` | Upload raw JPEG, PNG, or WebP bytes. Returns the updated profile. |
| POST | `/api/profile/deletion` | Issue a short-lived account deletion nonce for the current session. |
| DELETE | `/api/profile` | Verify the nonce and signed browser proof, delete the account and its sessions, and clear its cookies. |

See [authentication and account profile](auth.md) for request bodies, responses, and authentication rules.

## Android gateway routes in the Bun mock

| Method | Current path | Purpose |
| --- | --- | --- |
| GET | `/` | Confirm the Bun mock is running. |
| WebSocket | `/gateway` | Deliver `work_available` notices. |
| POST | `/jobs/claim` | Return one assignment as `{ id, attempt, to, text }`, or `204 No Content` when the queue is empty. |
| POST | `/jobs/{job_id}/result` | Save the phone’s result and acknowledge it with `{ id, attempt }`. |

The current Android app uses the WebSocket, claim, and result paths above. See the [Android gateway contract](android-gateway.md#server-contract) for the report payload and acknowledgment behavior.

## Planned Payra SMS routes

All SMS routes share the `/api/sms` prefix. The browser uses its signed-in session. Android uses a gateway credential for the WebSocket and both HTTP requests.

| Caller | Method | Path | Purpose |
| --- | --- | --- | --- |
| Browser | POST | `/api/sms/jobs` | Validate and schedule one job per recipient and send time in a single transaction. |
| Browser | GET | `/api/sms/jobs` | List the signed-in account’s jobs and their current statuses. |
| Browser | GET | `/api/sms/recipients` | List the account’s saved recipients. |
| Browser | POST | `/api/sms/recipients` | Save a phone number with an optional name. |
| Browser | PATCH | `/api/sms/recipients/{recipient_id}` | Edit a saved recipient. |
| Browser | DELETE | `/api/sms/recipients/{recipient_id}` | Remove a saved recipient from the list. |
| Android | WebSocket | `/api/sms/gateway/ws` | Connect and receive `work_available` notices. Replaces `/gateway`. |
| Android | POST | `/api/sms/gateway/jobs/claim` | Claim one job or get its immediate retry. Replaces `/jobs/claim`. |
| Android | POST | `/api/sms/gateway/jobs/{job_id}/result` | Report a try’s outcome and receive its acknowledgment. Replaces `/jobs/{job_id}/result`. |

Submission validates the final message’s SMS part count before inserting any `sms_jobs` rows. The worker wakes after commit and reads the earliest queued send time. Its WebSocket notice tells Android to claim the job over HTTP.

The first claim starts one `sms_attempts` row for the job. If try 1 returns `failed` or `partial` within the deadline, Android’s next claim gets the same job as try 2. Payra updates the same attempt row and retains both results. A missing report triggers a review after the timeout.

Payra commits the result before sending `200 OK` with `{ id, attempt }`. Repeating a saved result returns the matching acknowledgment without starting another send.
