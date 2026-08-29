# PLAN — MeowPay send-treats slice

**Backend build steps + DB schema (for critic review):** [docs/backend/PLAN.md](docs/backend/PLAN.md)

**Frontend build steps (for critic review):** [docs/frontend/PLAN.md](docs/frontend/PLAN.md)

**Shipped API the UI must follow:** [docs/backend/CONTRACT.md](docs/backend/CONTRACT.md)

**Role:** builder. Critic pass 3: no blockers. Pass-3 should-fixes are closed below. **Plan only — do not implement until the human approves.**

**Graded path:** clone → one Compose command → open the web app → send treats. Live deploy is a later task, not part of this plan.

## Goal

A reviewer clones the repo, starts Docker Compose, opens a web app, signs in as a cat (or registers), sees a 100-treat balance, sends treats to another cat, watches their balance and history update, and gets a real error on insufficient funds. Money lives in a ledger in Postgres. Retrying the **same submit** does not double-pay. Two overlapping sends cannot create treats.

Auth exists only to prove who is sending. The UI exists only to complete that path.

## What we are building (and what we are not)

From `SCOPE.md` and the takehome PDF. PDF requires a thin real slice, public repo, commit history, README. Ledger, JWT, Compose, and backend tests are **SCOPE choices**, not PDF requirements — we still ship them.

**Must ship**

- Web app: register / log in; then a send screen as *this* cat (own balance, pick recipient, amount, confirm, success/failure, recent transfers). Sender is never chosen in the UI.
- Auth: username + password, Bearer JWT. Username is the identifier. No email, OAuth, refresh tokens, password reset, KYC.
- Signup bonus: new cats get **100 treats as a ledger credit**. Not a default column on the cat.
- API: auth; transfer as the current user; own wallet/balance; list other cats as recipients; own transfer history.
- Persistence: Postgres. Restart does not invent money back. Passwords hashed.
- Money rules: amount is a **positive integer**; sender ≠ recipient; sender has enough; debit and credit in one transaction. Sender comes from the JWT (`sub` = cat id). Extra body fields such as `senderUsername` are ignored.
- Demo data: 3 seeded cats, passwords in the README, each with 100 treats.
- Run: README + Docker Compose (Postgres + API + web) from a clean clone, **one command, no `.env` copy step**.
- Ledger: source of truth. Transfer = debit + credit. Signup = one credit. Balance = `SUM(ledger_entries.amount)` computed **after** the sender row is locked.
- Idempotent send: transport retry of the same submit (same key) does not double-send.
- Transfer statuses `COMPLETED` and `REJECTED` are **in the slice**, not polish. `REJECTED` is sender-only (see history contract).
- Tests: **backend only**, including the concurrency and idempotency cases listed under Testing.

**Polish (cut from the bottom if time runs out — does not include statuses or the money path)**

1. History that is useful in the UI (who, amount, when, status) — API already returns this; polish is how clearly the screen shows it.
2. UI that is obvious: empty/error/success states, readable layout. Stop when it is clear, not pretty.
3. README “what we skipped” as decisions — especially: top-up is the same ledger credit as signup; we did not build a funding rail.

**Out**

Mobile/Flutter, human top-up UI, sender picker, real auth platform, frontend tests, locking *paper*, FX/fees, notifications, admin, design-system chrome, **hosted deploy in this plan**.

**Where we impress:** ledger + lock-before-SUM + idempotency + backend tests. Not auth. Not UI.

**Submission extras:** commit as we go (no squash-to-one); public GitHub; README covers what we built, how to run, what we skipped and why, how AI was used.

---

## Approach — money first, thin UI as soon as send works, deploy later

Greenfield. One repo: `backend/` (Spring Boot, **Java 21**, **Maven**) and `web/` (Vite + React + TypeScript), Compose at the root.

Java stays (SCOPE lock; we ship faster in Java than Kotlin). Hosted deploy is **parked** until local one-command works and a human asks for it.

Do **not** wait for a full test suite before anything is clickable. As soon as `POST /api/transfers` works, put a brutally thin send UI in front of it so the PDF path exists. Tests are still required and must include the overlapping-send case before we call the money path done.

### Phase 0 — Skeleton that can actually boot

- Spring Boot 3 / Java 21 / **Maven** in `backend/`. Flyway on, `ddl-auto` off.
- Root `docker-compose.yml`:
  - **Postgres has a healthcheck** (`pg_isready` or equivalent).
  - API `depends_on: postgres: condition: service_healthy`. No “started container” race.
  - Web service last.
- Local JWT: **demo-only default** in `application.yml` (obviously not for production). Compose uses that default. **No `.env` copy required** for the one-command path.
- `.env.example` exists for a future deploy (placeholders only). Never commit a real secret.
- CORS is **not a stub** — see constraints below. Wired in this phase so the browser path cannot 401 on OPTIONS.

**CORS / OPTIONS / Security (constraint, not later):**

- Echo origin **`http://localhost:5173` only**. Not `*`, not `127.0.0.1`.
- Allowed headers: `Authorization`, `Content-Type`, `Idempotency-Key`.
- Allowed methods include `OPTIONS`, `GET`, `POST`.
- `OPTIONS` is unauthenticated and runs **before** the Bearer filter.
- **`SessionCreationPolicy.STATELESS`, CSRF off.** `permitAll` on `OPTIONS` and `/api/auth/**`.
- JWT via **jjwt** (or Nimbus), HS256, expiry checked. No hand-rolled parser.

**Browser API URL (locked):**

- API published at **`8080`**. Web published at **`5173`** (container may listen on 80; host map is `5173:80`).
- `VITE_API_URL=http://localhost:8080` (browser, baked in at `npm run build`). Dockerfile must pass this build-arg into the build.
- README: open **`http://localhost:5173` only**.
- **Never** `http://api:8080` in `VITE_API_URL`.

### Phase 1 — Schema + ledger model

Three tables, one migration:

| Table | Role |
| --- | --- |
| `cats` | id (PK), username **stored lowercase**, unique on that value, password hash. No balance column as source of truth. |
| `ledger_entries` | money: signed **integer** amount, cat, type (`SIGNUP_BONUS` / `TRANSFER_DEBIT` / `TRANSFER_CREDIT`), optional transfer id. Partial **`UNIQUE (transfer_id, type)`** where `transfer_id IS NOT NULL` (one debit and one credit per completed transfer). |
| `transfers` | sender, recipient, amount (integer), status (`COMPLETED` \| `REJECTED`), **idempotency_key `NOT NULL`**, unique `(sender_id, idempotency_key)`. |

Balance = **`COALESCE(SUM(ledger_entries.amount), 0)`** for that cat. A SUM of no rows is SQL `NULL`; treat that as 0, never unbox null.

**Sign convention (schema must match):** debit amount is **−N**, credit / signup amount is **+N**. `type` is metadata only. Two `+N` rows create money — the happy-path test (sender −N, recipient +N) is what catches that.

No cached balance column unless we add it later in the **same** transaction as the entries.

Signup and transfer both write ledger rows. A future top-up is another credit with a different type; we do not build that rail.

### Phase 2 — Auth (thin)

- `POST /api/auth/register` and `POST /api/auth/login`.
- Usernames **trimmed + lowercased** on register and login. Trim **before** the blank check. After trim: empty or `> 64` → 400 `VALIDATION`. Unique index on the normalized value. Parallel register of the same name → 409 `USERNAME_TAKEN`, not 500 (outer method not `@Transactional`; catch unique violation after the inner TX).
- Passwords hashed (BCrypt). Empty or `> 72` → 400 `VALIDATION` (BCrypt only uses 72 bytes). JWT HS256 via **jjwt**, **TTL 7 days**, **`sub` = cat id** (stable PK). Username is login + display only. No refresh. Do not log password or `Authorization`.
- Filter: protected routes require `Authorization: Bearer …`. `OPTIONS` and `/api/auth/**` are open. **STATELESS + CSRF disabled.** CORS is a **global** `CorsConfigurationSource` in the security chain (not `@CrossOrigin` on a later controller).
- Register, in **one transaction**: insert cat + ledger credit of +100 (`SIGNUP_BONUS`).
- `@ControllerAdvice` maps `HttpMessageNotReadableException` / bind errors to `{ "error": "VALIDATION", "message": "..." }`. Tests for `10.5` assert the **code**, not only 400. Uncaught → `{ "error": "INTERNAL", "message": "..." }` with no Hibernate/SQL text.

### Phase 3 — Seeded demo cats

- 3 cats, usernames already lowercase, each with signup-bonus +100.
- Password hashes in the seed; plaintext only in the README.
- Seed idempotent on restart (Flyway or a runner that no-ops if rows exist).

### Phase 4 — Reads needed to send

- `GET /api/me` (username + ledger SUM).
- `GET /api/recipients` (other cats, never self).
- History can wait until after send exists; empty list is fine until then.

### Phase 5 — Send (the product)

**Transfer algorithm — this order, one DB transaction. The lock is a step, not a footnote.**

Validation that does not need money or the lock may run first (and fail without opening a transaction if we want): missing/blank/`> 128` `Idempotency-Key` → 400 `VALIDATION`; amount missing, ≤ 0, or **not an integer** (e.g. `10.5`) → 400 `VALIDATION`; recipient missing → 400 `VALIDATION`. After trim: username empty or `> 64`, password empty or `> 72` → 400 `VALIDATION` (BCrypt 72-byte limit). Trim username **before** the blank check. **Trim + lowercase `recipientUsername`** before lookup and before comparing payloads. Recipient not found → 404 `NOT_FOUND`; recipient is sender → 400 `SAME_CAT`. Sender = JWT cat id. Ignore `senderUsername` in the body (do **not** set `FAIL_ON_UNKNOWN_PROPERTIES`).

Idempotency fingerprint is **recipient id + amount**, not the raw username string (`Milo` vs `milo` is the same payload).

Then, **inside one transaction**:

1. **Lock the sender row** (`SELECT … FOR UPDATE` on `cats` by id).
2. **Idempotency lookup** on `(sender_id, idempotency_key)`.
   - Hit + same fingerprint → return the **original outcome** (see replay table). No SUM, no new ledger rows.
   - Hit + different fingerprint → 409 `IDEMPOTENCY_CONFLICT`. No new movement.
   - Miss → continue.
3. **`COALESCE(SUM(ledger_entries.amount), 0)` for the sender.** This SUM is **after** the lock. Computing it before the lock is incorrect.
4. If `SUM < amount`: insert `transfers` row `REJECTED` (key stored, **no** ledger rows) → **409 `INSUFFICIENT_FUNDS`**.
5. If `SUM >= amount`: insert `transfers` row `COMPLETED` + debit **−N** + credit **+N** → **201** transfer body. Both ledger rows or neither.

**Unique-constraint race (same key, two in-flight):** outer method is **not** `@Transactional`. Inner TX does lock → lookup → SUM → insert. Catch the unique violation **after** that TX has ended → `SELECT` by `(sender_id, key)` → return that row as a replay (200 / 409 per the table). If the row is missing (winner rolled back), **retry the use-case once**. Do not catch `DataIntegrityViolationException` inside `@Transactional` (rollback-only → 500). Same outer-catch pattern as register.

Two different keys on the same sender serialize on the row lock, so both cannot pass a stale SUM.

README may note we did not write a locking paper. The lock + post-lock SUM is still required.

### Phase 6 — Thin send UI (as soon as Phase 5 works)

Do not wait for a complete test matrix. The PDF check is: can a human send treats.

SPA talks to the API with the Bearer token. No Next.js, no Supabase client, no cookies.

- Auth: register / log in. JWT in `localStorage` (README skip vs httpOnly).
- **401** (expired/bad token) → clear token, return to login.
- Send screen: this cat’s name + balance; recipient picker; integer amount; confirm.
- **Idempotency keys:** a **new user click** (new submit) gets a **new** UUID. Only a **transport retry** of that same in-flight request reuses the key (timeout, axios retry). **Disable the button to prevent a double fire.** If two requests still go out with two keys, the lock handles it. Reusing a key on a new click after `REJECTED` is a trap; we will not do it.
- Client switches on the JSON **`error` code**, not HTTP status. `INSUFFICIENT_FUNDS` ≠ `USERNAME_TAKEN` ≠ `VALIDATION` ≠ `SAME_CAT`.
- **`POST /api/transfers` is success on HTTP 200 or 201** with a transfer body and no `error` field. Replay of a committed send is 200; treating only 201 as success causes a second click (new key) and a double-send in the product.
- Success (Phase 6): refetch **`GET /api/me` only**. History refetch starts in Phase 7, when that route exists. Failure: show the `message`. No crash.

### Phase 7 — History API + UI

- `GET /api/me/transfers` as contracted below.
- Show who, amount, when, status on the send screen.

### Phase 8 — Backend tests (required; start once Phase 5 exists)

Testcontainers Postgres. Must be green before we call the money path done. List is under Testing — implement from that list, not from memory.

Thin UI (Phase 6) may already be clickable while these land.

### Phase 9 — One-command run story + README

- Compose: healthy Postgres → API → web. Reviewer needs Docker, not a JDK/Node toolchain.
- Web image / Compose build arg: `VITE_API_URL=http://localhost:8080` (or the published API port).
- README: what we built, `docker compose up --build`, open `http://localhost:5173` only, **wait until the API is up (~40–60s on first boot)**, demo cat passwords, JWT default is demo-only, what we skipped (top-up rail, refresh tokens, httpOnly cookies, hosted deploy), how AI was used (builder/critic). Run the API via Compose; do not publish Postgres 5432.
- No “copy `.env` first.” No deploy sleep warning until we actually deploy.

### Later (not this plan) — hosted demo

Only after the one-command local path works and a human asks. Vercel (web) + Render/Railway (API) + Supabase Postgres (direct/session, no Supabase Auth). Secrets only in host env. Different `VITE_API_URL` and CORS origin. Until then, do not spend slice time on it.

---

## FE ↔ BE contract

**Source of truth for the shipped API (including health, 401 message split, CORS `localhost` vs `127.0.0.1`):** [docs/backend/CONTRACT.md](docs/backend/CONTRACT.md). Do not rename silently. All JSON. Amounts are **JSON integers** (server type `int` / `long`, not `Double` / `BigDecimal`). Auth header on every non-auth, non-OPTIONS route: `Authorization: Bearer <token>`.

**401 split (easy to get wrong):** login/register `UNAUTHORIZED` is a form error (`"Wrong username or password."`). Protected-route `UNAUTHORIZED` (`"Please sign in."`) clears the token and returns to login. Do not treat a failed login as a dead session or a network failure.

### Errors (all 4xx/5xx)

```json
{ "error": "INSUFFICIENT_FUNDS", "message": "Not enough treats." }
```

`error` is a stable machine code. The FE branches on `error`, never on status alone.

| HTTP | `error` | When |
| --- | --- | --- |
| 400 | `VALIDATION` | missing/blank fields, **missing/blank/`> 128` `Idempotency-Key`**, username empty/`> 64`, password empty/`> 72`, amount missing / ≤ 0 / **not an integer**, malformed JSON |
| 400 | `SAME_CAT` | recipient is the sender |
| 401 | `UNAUTHORIZED` | bad/missing JWT, bad login |
| 404 | `NOT_FOUND` | recipient username does not exist |
| 409 | `USERNAME_TAKEN` | register with existing (normalized) username |
| 409 | `INSUFFICIENT_FUNDS` | send when post-lock SUM < amount (first time **and** replay) |
| 409 | `IDEMPOTENCY_CONFLICT` | same key, different `recipientUsername` or `amount` |
| 500 | `INTERNAL` | uncaught; `message` has no Hibernate/SQL text |

### Replay table (`POST /api/transfers`, same sender)

| First outcome | Store row? | Store key? | Replay same key + same body |
| --- | --- | --- | --- |
| `COMPLETED` | yes + two ledger rows | yes | **200** transfer body, no second movement |
| `INSUFFICIENT_FUNDS` | yes, `REJECTED`, no ledger | yes | **409** `INSUFFICIENT_FUNDS` again, **never 200** |
| `VALIDATION` / `SAME_CAT` / `NOT_FOUND` | no | no | re-evaluate (not an attempt) |

First `COMPLETED` is **201**. Replay of `COMPLETED` is **200**. Replay of insufficient is **409**, not 200-with-a-rejected-body. Map HTTP status **by stored outcome**, never `replay=true` → 200.

Sticky reject on the **same key** is intentional: that key means “this submit.” A later send after the balance changes is a **new click → new key**.

### `POST /api/auth/register`

Request: `{ "username": string, "password": string }`  
Username is trimmed + lowercased before insert.  
Response **201**: `{ "token": string, "username": string }` (username already normalized).  
Side effect: cat + ledger credit +100.  
Errors: `VALIDATION`, `USERNAME_TAKEN`.

### `POST /api/auth/login`

Request: `{ "username": string, "password": string }`  
Username trimmed + lowercased before lookup.  
Response **200**: `{ "token": string, "username": string }`  
Errors: `VALIDATION`, `UNAUTHORIZED`.

### `GET /api/me`

Response **200**: `{ "username": string, "balance": <integer> }`  
Balance is the ledger sum.

### `GET /api/recipients`

Response **200**: `[ { "username": string } ]`  
Every cat except the caller.

### `POST /api/transfers`

Headers: `Idempotency-Key` **required**, non-blank. Column `NOT NULL`.  
Request: `{ "recipientUsername": string, "amount": <integer> }`  
No sender field. If `senderUsername` is present, **ignore it**.

Response **201** (first `COMPLETED`):

```json
{
  "id": "<uuid>",
  "senderUsername": "luna",
  "recipientUsername": "milo",
  "amount": 10,
  "status": "COMPLETED",
  "createdAt": "<iso-8601 UTC Instant>"
}
```

### `GET /api/me/transfers`

Newest first.

**Visibility**

- `COMPLETED` the cat **sent**: included, `direction: "OUT"`.
- `COMPLETED` the cat **received**: included, `direction: "IN"`.
- `REJECTED`: included **only for the sender**, `direction: "OUT"`. Recipients must **not** see another cat’s failed send as inbound.

```json
[
  {
    "id": "<uuid>",
    "counterpartyUsername": "milo",
    "direction": "OUT",
    "amount": 10,
    "status": "COMPLETED",
    "createdAt": "<iso-8601 UTC Instant>"
  }
]
```

`status` is `COMPLETED` | `REJECTED`. It is part of the must-ship contract, not a cuttable polish flag.

---

## Files we expect to touch

| Path | Why |
| --- | --- |
| `PLAN.md` | this plan |
| `README.md` | run story, demo cats, skips, AI |
| `.env.example` | deploy placeholders only; not required to run locally |
| `.gitignore` | Java / Node / env |
| `docker-compose.yml` | Postgres (healthcheck) + API (waits) + web (browser `VITE_API_URL`) |
| `backend/pom.xml`, `Dockerfile` | Spring Boot 3 / Java 21 / Maven |
| `backend/src/main/resources/application.yml` | datasource, **demo JWT default**, 7d TTL, Flyway, CORS |
| `backend/src/main/resources/db/migration/V1__*.sql` | cats, ledger, transfers (`idempotency_key NOT NULL`) |
| seed migration or runner | demo cats |
| `backend/src/main/java/...` | app, security/JWT (`sub` = cat id), CORS, register/login, ledger, transfers |
| `backend/src/test/java/...` | money-path tests (Testcontainers) |
| `web/package.json`, Vite + TS | SPA |
| `web/src/` | auth, send, history, API client (`error` switch, 401 → login) |
| `web/Dockerfile` | Compose web; build-arg browser API URL |

Do **not** put product code under `docs/`, `builder-critic-setup/`, or agent skill files. No Render/Vercel config in this plan.

---

## Decisions (closed)

1. **Integer treats.** Server binds `int`/`long`. Non-integers (`10.5`, `1e2` if bound as non-int) → 400 `VALIDATION`. Not `Double`.
2. **Idempotency via `Idempotency-Key` header**, unique `(sender_id, key)`, column `NOT NULL`. New click = new key. Transport retry = same key.
3. **Sender only from JWT `sub` (cat id).** Body is recipient + amount. Extra sender fields ignored.
4. **`REJECTED` is in the slice.** Persist on insufficient funds (no ledger rows), store the key, return **409** first time and on replay. History: sender-only for `REJECTED`. Replay table above is the protocol — not 200-on-replay, not “optional polish.”
5. **Cached balance:** SUM only for now.
6. **JWT in `localStorage`.** README skip vs httpOnly. 401 → login.
7. **Maven.** Not Gradle.
8. **Three demo cats**, 100 treats, no pre-seeded transfers.
9. **Usernames trimmed + lowercased** on register, login, and `recipientUsername`. Unique on the normalized value. Idempotency fingerprint is recipient **id** + amount.
10. **JWT TTL = 7 days.** Demo default signing key in local `application.yml`; README: never reuse in production.
11. **Java, not Kotlin.** SCOPE lock; faster for us. Their ramp bonus left on the table unless a human overrides.
12. **Hosted deploy is out of this plan.** Local Compose + tests + send UI, then stop.

---

## Risks / unknowns (only what is still actually open)

- **`SELECT FOR UPDATE` vs `SERIALIZABLE`:** we will use row lock + post-lock SUM. SERIALIZABLE is a cheap swap if a later critic prefers it; not an open product decision.
- **Time:** if squeezed, keep lock+SUM, replay table, CORS, Compose health, overlapping-send test, and a clickable send. Cut UI polish and README prose last. Do not cut the lock or the overlapping-send test.

---

## How we will test

**Backend (required, automated) — implement from this list**

- Register → balance 100 from ledger, not a default column.
- Transfer happy path: sender **−N**, recipient **+N**, two ledger rows, `COMPLETED`, **201**.
- Insufficient funds: no ledger rows, `REJECTED` stored for sender, **409** `INSUFFICIENT_FUNDS`, recipient history unchanged.
- Replay same key + same body after `COMPLETED` → **200**, one movement.
- Replay same key + same body after `INSUFFICIENT_FUNDS` → **409** again, still no ledger rows.
- Same key, **different** amount or recipient → `IDEMPOTENCY_CONFLICT`, still one movement (the first). Same key + `Milo` vs `milo` is **not** a conflict.
- Missing `Idempotency-Key` → 400 `VALIDATION`. Blank key → 400 `VALIDATION`. Key `> 128`, username `> 64`, password `> 72` → 400 `VALIDATION` (not 500).
- Amount ≤ 0 → 400 `VALIDATION`. Amount `10.5` (non-integer) → 400 **and** `error: "VALIDATION"` (not only status).
- Same-cat → 400 `SAME_CAT`. Unknown recipient → 404. `recipientUsername: "Milo"` finds `milo`.
- Extra body field `senderUsername` (other cat): JWT cat is still the sender; that other cat is not debited.
- **Two overlapping sends (must overlap in time — two threads / latch / parallel HTTP, not sequential MockMvc, not a class-level `@Transactional` test), different keys, balance 100, amounts 80 and 80:** one `COMPLETED`, one `INSUFFICIENT_FUNDS` / `REJECTED`, **sender balance = 20** (not −60). Global `SUM(all ledger)` staying flat does **not** prove the lock (two successful 80s still conserve the world total). Sequential 80 then 80 may exist as a weaker extra case; it does not prove the lock.
- Unique-constraint race / double submit same key (including **parallel same-key** HTTP): no 500; replay behavior as table; unique catch is outside the inner TX.
- History as Milo does **not** include Luna→Whiskers.
- `GET /api/me` and `GET /api/recipients` have **no** `password` / `passwordHash`.
- Parallel register of the same username (two threads, not sequential exists-check) → 409 `USERNAME_TAKEN`, not 500.

**Manual (reviewer path, once UI exists)**

- Clean clone, `docker compose up --build`, no `.env` copy.
- Open the **web** origin (not curl-only). Log in as a seeded cat; see 100; send; balance + history update.
- Register a new cat; see 100; send.
- Overspend: error from `INSUFFICIENT_FUNDS`, no crash, recipient does not see a fake inbound.
- Refresh / retry the same submit: no double-send.
- Register a duplicate / `Luna` vs `luna`: one cat.

**Not doing:** Cypress/Playwright, mobile, load tests, hosted-deploy verification in this plan.

---

## Suggested commit story (do not squash)

1. Skeleton: Compose Postgres (healthcheck) + Spring Boot + Flyway + CORS + demo JWT default.
2. Schema + ledger types.
3. Auth + lowercase usernames + signup bonus + seed.
4. Me + recipients.
5. Transfers: lock → idempotency → SUM → reject or both ledger rows.
6. Thin web send slice (browser `VITE_API_URL`).
7. History API + UI.
8. Backend tests (concurrency + replay + missing key), may start with commit 5.
9. Compose web + README (one command).

No deploy commit in this story.

---

## Critic findings — builder response

| # | Verdict | What changed |
| --- | --- | --- |
| 1 | **Fixed** | Phase 5 is ordered: lock sender → idempotency lookup → **then** SUM → reject or both ledger rows. Lock is a step. |
| 2 | **Fixed** | Closed replay table. `REJECTED` + key stored; replay is **409 never 200**. New click = new key; only transport retries reuse the key. |
| 3 | **Fixed** | CORS constraints in Phase 0: concrete origin, `Authorization` + `Idempotency-Key`, OPTIONS before JWT. |
| 4 | **Fixed** | Local `VITE_API_URL` is a browser URL (`localhost`), not `http://api:8080`. |
| 5 | **Fixed** | Postgres healthcheck + API `depends_on` healthy. |
| 6 | **Fixed** | `REJECTED` is sender-only; recipients see `COMPLETED` inbound only. |
| 7 | **Fixed** | Key required, `NOT NULL`, blank → 400 `VALIDATION`; tests listed. |
| 8 | **Fixed** | Amount is JSON integer / `int`/`long`; non-integers → 400. |
| 9 | **Fixed** | Decision 9: lowercase on register and login; unique on normalized value. |
| 10 | **Fixed** | Demo JWT default in `application.yml`; no required `.env` copy; `.env.example` is for later deploy. |
| 11 | **Fixed** | FE switches on `error`, not status. |
| 12 | **Fixed** | JWT `sub` = cat id. |
| 13 | **Fixed** | Unique violation → SELECT → return row; if missing, retry once; no 500. |
| 14 | **Fixed** | `REJECTED` / `status` are must-ship. Polish list no longer pretends they are cuttable. |
| 15 | **Fixed** | Testing list includes overlapping 80+80, missing key, conflict, insufficient replay, replay 200 vs 409, extra `senderUsername`. |
| 16 | **Fixed** | Hosted deploy removed from the critical path (human agreed). Parked as “later.” |
| 17 | **Fixed** | Thin send UI as soon as `POST /api/transfers` works; tests required but not a gate in front of any clickable slice. |
| Nit Kotlin | **Won’t fix** | SCOPE locked Java; we are faster in Java. Human can override. |
| Nit Maven | **Fixed** | Maven, not Gradle. |
| Nit JWT TTL | **Fixed** | 7 days. |
| Nit 401 → login | **Fixed** | Phase 6. |
| P3-1 | **Fixed** | Transfers succeed on **200 or 201** with a transfer body (no `error`). |
| P3-2 | **Fixed** | STATELESS, CSRF off, jjwt, permitAll OPTIONS + `/api/auth/**`. |
| P3-3 | **Fixed** | `@ControllerAdvice` → `VALIDATION`; `10.5` test asserts the code. |
| P3-4 | **Fixed** | Overlapping-send test must overlap in time (threads/latch). |
| P3-5 | **Fixed** | Trim+lowercase recipient; fingerprint is recipient id + amount. |
| P3-6 | **Fixed** | `COALESCE(SUM, 0)`; debit −N / credit +N. |
| P3-7 | **Fixed** | Ports locked: API 8080, web 5173, CORS/README `http://localhost:5173`. |
| P3-8 | **Fixed** | Phase 6 refetches `/api/me` only. |
| P3-9 | **Fixed** | History isolation, no password leak, register unique → 409. |
| P3-nit button | **Fixed** | Disable to prevent double fire; lock is backup. |
| P3-nit SCOPE | **Won’t fix here** | SCOPE polish #1 is stale vs this plan; statuses stay must-ship. Not a runtime bug. |
| P3-nit Instant | **Fixed** | `createdAt` is UTC `Instant`. |
| P3-nit boot wait | **Fixed** | README: wait ~20–30s for first API boot. |
| B-1 | **Fixed** | Step 5 proves bonus with SQL; `GET /api/me` is Step 8. See [docs/backend/PLAN.md](docs/backend/PLAN.md). |
| B-2 | **Fixed** | Overlapping lock proof is sender = 20 + one 201/one 409, not global SUM. |
| B-3 | **Fixed** | Unique-violation catch is outside `@Transactional` for register and transfer. |
| B-4 | **Fixed** | Money tests are HTTP against a committed DB; no class-level `@Transactional` on the lock case. |
| B-5 | **Fixed** | Recipients: never self, include milo+whiskers; exact-two only after `down -v`. |
| B-6 | **Fixed** | CORS is a global security-chain bean; OPTIONS works before transfer mapping. |
| B-7 | **Fixed** | JRE image installs curl; Compose `start_period` 40–60s. |
| B-8 | **Fixed** | API via Compose only; do not publish 5432. |
| B-9 | **Fixed** | Username ≤64, key ≤128, password ≤72 after trim; else `VALIDATION`. |
| B-10 | **Fixed** | Partial `UNIQUE (transfer_id, type)`; do not fail on unknown JSON fields. |
| C-1 | **Fixed** | Transfer status by outcome: COMPLETED 201/200; INSUFFICIENT first and replay **409**. Never `replay=true` → 200. |
| C-2 | **Fixed** | Compose sets DB user/password; Flyway off until V1. |
| C-3 | **Fixed** | Overlap uses a fresh 100-treat sender; no mid-script `down -v`; Luna→whiskers is an explicit send. |
| C-4 | **Fixed** | Tests inject datasource url/user/password. |
| C-5 | **Fixed** | Empty-ledger `/api/me` authenticates as that cat (login or minted JWT). |
| C-6 | **Fixed** | No INTERNAL assertion on unknown URL (that is 404). |
| C-7 | **Fixed** | Parallel-register test in the automated suite. |

---

## Stop line

Backend is implemented. Frontend plan: [docs/frontend/PLAN.md](docs/frontend/PLAN.md). **Do not implement the UI** until the human explicitly approves a frontend step.
