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
| GET | `/api/sms/recipients` | List the signed-in account’s saved recipients. |
| POST | `/api/sms/recipients` | Save a phone number with an optional name. |
| PATCH | `/api/sms/recipients/{recipient_id}` | Update the signed-in account’s saved recipient. |
| DELETE | `/api/sms/recipients/{recipient_id}` | Remove a saved recipient from the signed-in account’s list. |
| POST | `/api/sms/jobs` | Save one job per recipient and send time in a single transaction. |
| WebSocket | `/api/sms/gateway/ws` | Authenticate the Android gateway and deliver `work_available` notices. |
| POST | `/api/sms/gateway/jobs/claim` | Return the gateway’s outstanding assignment or claim one due SMS job. |
| POST | `/api/sms/gateway/jobs/{job_id}/result` | Save the send outcome and acknowledge the reported attempt. |

See [authentication and account profile](auth.md) for request bodies, responses, and authentication rules.

`POST /api/sms/jobs` accepts `{ message, recipient_ids, send_times }`. Recipient IDs must belong to the signed-in account, and send times are RFC 3339 timestamps. Payra copies each contact’s current number into the jobs. If any selected contact is unavailable, no jobs are saved. A successful save returns `201 Created` with `{ job_ids }`.

`PATCH /api/sms/recipients/{recipient_id}` accepts `phone`, `name`, or both. Omitted fields keep their current values. A `null` or blank name clears it. A supplied phone must be a valid Bangladeshi mobile number. The route requires an active session and returns `200 OK` with `{ id, phone, name }`. It returns `404` for an unavailable recipient and `409` if another recipient on the account already uses that number. Existing jobs keep the number saved when they were scheduled.

`DELETE /api/sms/recipients/{recipient_id}` requires an active session and returns `204 No Content` after removing the recipient. An unavailable recipient returns `404`. Existing jobs keep the number saved when they were scheduled.

The gateway sends `Authorization: Bearer <key>` on the WebSocket handshake and both HTTP calls. Payra checks it against `GATEWAY_API_KEY_SHA256` and returns `401` for a missing or incorrect key.

`POST /api/sms/gateway/jobs/claim` uses the same gateway key. It returns `200 OK` with `{ id, attempt, to, text }` or `204 No Content` when no job qualifies. An outstanding assignment is returned with the same attempt number so the phone can recover a lost claim reply. A new claim picks the earliest due `next_attempt_at` within `send_by`, marks the job `sending`, and records its attempt before returning it.

`POST /api/sms/gateway/jobs/{job_id}/result` accepts `{ attempt, status }`. Status is `sent`, `failed`, `partial`, or `unknown`. Payra commits the result before returning `200 OK` with `{ id, attempt }`. A matching replay receives the same acknowledgment. A stale attempt or a conflicting result gets `409`, and an assignment that does not exist gets `404`.

Payra allows two total attempts. It requeues a failed, partial, or unknown first attempt for 15 minutes later if that time is before `send_by`. Requeueing and saving the outcome share a transaction that wakes the scheduler on commit. The next claim increments the attempt on the same row and clears the previous result.

## Android gateway routes in the Bun mock

| Method | Current path | Purpose |
| --- | --- | --- |
| GET | `/` | Confirm the Bun mock is running. |
| WebSocket | `/gateway` | Deliver `work_available` notices. |
| POST | `/jobs/claim` | Return one assignment as `{ id, attempt, to, text }`, or `204 No Content` when the queue is empty. |
| POST | `/jobs/{job_id}/result` | Save the phone’s result and acknowledge it with `{ id, attempt }`. |

These are the routes used for the earlier Bun mock tests.

## Planned Payra SMS routes

All SMS routes share the `/api/sms` prefix. The browser uses its signed-in session. Android uses a gateway credential for the WebSocket and both HTTP requests.

| Caller | Method | Path | Purpose |
| --- | --- | --- | --- |
| Browser | GET | `/api/sms/jobs` | List the signed-in account’s jobs and their current statuses. |

### Deferred: Safe retries for Schedule SMS

If `POST /api/sms/jobs` commits but the browser loses the response, submitting again can create duplicate jobs. When the outcome is unclear, the form should say it couldn’t confirm the schedule.

The browser will generate one UUID per submission and reuse it when retrying the same request. Payra will save the account-scoped UUID and a request fingerprint in the same transaction as the jobs. A matching retry can return **Already scheduled** without creating more jobs. Reusing the UUID with different input returns `409 Conflict`. The submission record does not need to store job IDs.
