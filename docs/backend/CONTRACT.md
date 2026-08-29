# Shipped API contract

What the running backend actually does. The frontend must follow this file, not invent fields or status codes. Shapes were checked against the Java controllers/DTOs after backend Step 12.

All JSON. Amounts are JSON integers. Protected routes need `Authorization: Bearer <token>`. Extra request fields (for example `senderUsername`) are ignored.

CORS allows origin **`http://localhost:5173` only**. `http://127.0.0.1:5173` is a different origin and will fail preflight. Allowed methods: `GET`, `POST`, `OPTIONS`. Allowed headers: `Authorization`, `Content-Type`, `Idempotency-Key`.

## Errors (every 4xx/5xx)

```json
{ "error": "INSUFFICIENT_FUNDS", "message": "Not enough treats." }
```

The UI branches on `error`, not on HTTP status alone. Prefer the server `message` when present.

| HTTP | `error` | When |
| --- | --- | --- |
| 400 | `VALIDATION` | missing/blank fields, missing/blank/`> 128` `Idempotency-Key`, username empty/`> 64`, password empty/`> 72`, amount missing / ≤ 0 / not an integer (`10.5`), malformed JSON |
| 400 | `SAME_CAT` | recipient is the sender |
| 401 | `UNAUTHORIZED` | **login** unknown user or wrong password → `"Wrong username or password."` (same text both ways). **Protected route** missing/bad JWT → `"Please sign in."` |
| 404 | `NOT_FOUND` | recipient username does not exist |
| 409 | `USERNAME_TAKEN` | register, username already exists (after trim + lowercase) |
| 409 | `INSUFFICIENT_FUNDS` | send when post-lock SUM < amount (first time **and** replay of that key) |
| 409 | `IDEMPOTENCY_CONFLICT` | same key, different recipient or amount |
| 500 | `INTERNAL` | uncaught; `message` has no SQL |

Login `401` is a form error. Protected-route `401` means the session is dead: clear the token and show the auth screen. Do **not** treat a failed login as “session expired” or as a network failure.

## Replay table (`POST /api/transfers`, same sender)

| First outcome | Store row? | Replay same key + same fingerprint |
| --- | --- | --- |
| `COMPLETED` | yes + two ledger rows | **200** transfer body, no second movement |
| `INSUFFICIENT_FUNDS` | yes, `REJECTED`, no ledger | **409** `INSUFFICIENT_FUNDS` again, **never 200** |
| `VALIDATION` / `SAME_CAT` / `NOT_FOUND` | no | re-evaluate (not an attempt) |

First `COMPLETED` is **201**. Fingerprint is **recipient id + amount** (`Milo` ≡ `milo`). Fingerprint mismatch → `IDEMPOTENCY_CONFLICT`. New user click = new `Idempotency-Key`.

**The UI treats HTTP 200 or 201 as a successful send** when the body is a transfer and has no `error` field. Treating only 201 as success will make a replay look like a failure and a second click (new key) can double-send.

## Routes

### `GET /api/health`

No auth. **200** `{ "status": "up" }`.

### `POST /api/auth/register`

Request: `{ "username": string, "password": string }`  
Username is trimmed, then rejected if empty/`> 64`, then lowercased.  
Response **201**: `{ "token": string, "username": string }` (username already normalized).  
Side effect: cat + ledger credit **+100**.  
Errors: `VALIDATION`, `USERNAME_TAKEN`.

### `POST /api/auth/login`

Same request body.  
Response **200**: `{ "token": string, "username": string }`.  
Errors: `VALIDATION`, `UNAUTHORIZED` (see 401 split above).

### `GET /api/me`

Response **200**: `{ "username": string, "balance": <integer> }`  
Balance is `COALESCE(SUM(ledger), 0)` (Java `long` → JSON number). Never `password` / `passwordHash`.

### `GET /api/recipients`

Response **200**: `[ { "username": string } ]`  
Every cat except the caller. Never hashes. Empty array if the caller is the only cat.

### `POST /api/transfers`

Header `Idempotency-Key` required (non-blank, ≤ 128).  
Request: `{ "recipientUsername": string, "amount": <integer> }`  
No sender field. Sender is the JWT cat (`sub` = cat UUID).

Response **201** (first `COMPLETED`) or **200** (COMPLETED replay):

```json
{
  "id": "<uuid>",
  "senderUsername": "luna",
  "recipientUsername": "milo",
  "amount": 10,
  "status": "COMPLETED",
  "createdAt": "2026-08-29T19:29:26.289346087Z"
}
```

`createdAt` is an ISO-8601 UTC `Instant`. `status` on a success body is `COMPLETED`. Insufficient returns the error envelope, not a transfer body.

### `GET /api/me/transfers`

Newest first. Empty list is `[]`.

| Row | Included? | `direction` |
| --- | --- | --- |
| `COMPLETED` this cat sent | yes | `OUT` |
| `COMPLETED` this cat received | yes | `IN` |
| `REJECTED` this cat sent | yes | `OUT` |
| `REJECTED` someone else sent to this cat | **no** | — |

```json
{
  "id": "<uuid>",
  "counterpartyUsername": "milo",
  "direction": "OUT",
  "amount": 10,
  "status": "COMPLETED",
  "createdAt": "2026-08-29T19:29:26.289346087Z"
}
```

`status` is `COMPLETED` | `REJECTED`.

## Client rules the API assumes

- New user submit = new UUID key. Only a transport retry of that same in-flight request reuses the key.
- Do not send `senderUsername`.
- Do not open the app as `http://127.0.0.1:5173`.
- `VITE_API_URL` is a **browser** URL (`http://localhost:8080`), never `http://api:8080`.
