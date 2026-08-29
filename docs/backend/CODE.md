# Backend code

What the API code is and how the pieces connect. Filled in as each step lands.

## Layout

- `backend/` is a Maven module (Spring Boot **3.4.5**, Java **21**).
- Entry point: `com.meowpay.MeowPayApplication`. It excludes `UserDetailsServiceAutoConfiguration` so Boot does not invent a default user.

## What exists now

- `GET /api/health` → `{ "status": "up" }` (`HealthController`). Does not touch the database.
- Security is **STATELESS**, CSRF/form/basic off. Open: `OPTIONS /**`, `/api/auth/**`, `/api/health`. Everything else needs a Bearer JWT.
- CORS is a **global** `CorsConfigurationSource` (origin `http://localhost:5173` only; headers `Authorization`, `Content-Type`, `Idempotency-Key`). Preflight works before `/api/transfers` exists.
- `JwtService` issues/parses HS256 tokens; `sub` is the cat UUID. `JwtAuthFilter` parses `sub` only — it does not look up the cat row. Invalid/missing token on a protected route → `{ "error": "UNAUTHORIZED" }`.
- Errors: `{ "error", "message" }`. Bind / unreadable JSON / missing header → `VALIDATION`. Uncaught → `INTERNAL` with no SQL in `message`. Do not log password or `Authorization`.
- Jackson `accept-float-as-int` is **false**. Hibernate JDBC timezone is UTC.
- `POST /api/auth/register` → **201** `{ token, username }`. Username is trimmed, then rejected if empty/`> 64`, then lowercased. Password empty/`> 72` → `VALIDATION`. One inner TX writes the cat + `SIGNUP_BONUS` **+100**. The outer method is not `@Transactional`; a unique-name race becomes `USERNAME_TAKEN` (409), not 500.
- `POST /api/auth/login` → **200** same body. Unknown user and wrong password both return 401 `UNAUTHORIZED` with the same message. JWT `sub` is the cat id.
- `DemoDataSeeder` runs on boot and registers `luna`, `milo`, and `whiskers` (password `treats123`) if missing. Each gets the normal signup +100. Existing usernames (including olive on an old volume) are left alone. No seed transfers.
- `GET /api/me` → `{ username, balance }` from `COALESCE(SUM(ledger.amount), 0)`.
- `GET /api/recipients` → other cats as `{ username }` only (never self, never hashes).
- `POST /api/transfers` requires `Idempotency-Key`. Sender is the JWT cat. Body is `{ recipientUsername, amount }` (`senderUsername` is ignored). Inner TX: lock sender → lookup key → `COALESCE(SUM)` → `REJECTED` (no ledger) or `COMPLETED` (−N / +N). Outer method catches unique violations and replays. HTTP status is by **outcome**: COMPLETED 201/200, INSUFFICIENT first and replay **409**.
- `GET /api/me/transfers` is newest first. COMPLETED sent = `OUT`, received = `IN`. `REJECTED` is sender-only.

## Tests

`cd backend && mvn test` runs `MoneyPathTest` (Surefire `*Test`). The class talks HTTP via `TestRestTemplate` against a real committed Postgres — no class-level `@Transactional`.

- `TestPostgres` starts Postgres with Testcontainers (`postgres:16-alpine`). If the Java Docker client fails (common on Docker Engine 29), it falls back to `CliPostgres` (`docker run`). Either way, `@DynamicPropertySource` injects url / user / password. Compose is not the test database.
- Cases: signup 100, happy path −N/+N, insufficient first and replay both 409, COMPLETED replay 200, fingerprint conflict (amount **or** recipient), `Milo`/`milo` same key, VALIDATION (missing/blank/129-char key, 65-char username, 73-char password, amount 0 and `10.5`), SAME_CAT / NOT_FOUND, extra `senderUsername` ignored, overlapping 80+80 (sender ends at 20), same-key parallel 201+200, history isolation (Milo empty; Whiskers sees one **IN** / COMPLETED from Luna), parallel register 201+409, empty-ledger cat login → 0.
- Empty-ledger uses `JdbcTemplate` + `PasswordEncoder` so the cat can log in. There is no `GET /api/nope` INTERNAL assertion.

## Runbook

[README.md](../../README.md) is the how-to-run (Compose, demo cats, curl, `mvn test`). It does not explain the internals above. The shipped JSON contract for the frontend is [CONTRACT.md](CONTRACT.md). The web UI plan is [docs/frontend/PLAN.md](../frontend/PLAN.md).

## Schema (Flyway `V1__init.sql`)

Flyway is **on**. Three tables, no balance column on `cats`.

- `cats` — id, lowercase username, BCrypt hash, `created_at`.
- `transfers` — sender/recipient, positive `amount`, `COMPLETED`|`REJECTED`, `idempotency_key NOT NULL`, `UNIQUE (sender_id, idempotency_key)`.
- `ledger_entries` — signed `amount` (`−N` debit, `+N` credit/signup), type, `transfer_id` NULL only for `SIGNUP_BONUS`. CHECKs enforce sign and type vs `transfer_id`. Partial `UNIQUE (transfer_id, type)` stops two credits on one transfer.

Balance of a cat is `COALESCE(SUM(ledger_entries.amount), 0)` (`LedgerEntryRepository.sumBalance`). Sender lock is `CatRepository.lockById` (`FOR UPDATE`). Debit is negative; `type` is metadata only.
- Datasource username/password default to the demo role `meowpay` / `meowpay`. There is **no** JDBC URL in `application.yml` — Compose sets `SPRING_DATASOURCE_URL` (and user/password). We do not default to `localhost:5432`.

## How it runs (Compose)

Root `docker-compose.yml` has three services: `postgres`, `api`, and `web`. Postgres **5432 is not published** on the host.

- Postgres: `postgres:16-alpine`, db/user/password `meowpay`, healthcheck `pg_isready`.
- API: built from `backend/Dockerfile` (Maven package, then `eclipse-temurin:21-jre` **with curl installed** — the JRE image does not ship curl). Healthcheck hits `/api/health`, `start_period` 60s.
- Web: built from `web/Dockerfile` (`node:22-alpine` + `npm ci`, then `nginx:1.27-alpine`). Host **5173** maps to nginx **80**. `VITE_API_URL=http://localhost:8080` is baked in at image build — the browser calls localhost, never `http://api:8080`. Healthcheck is `wget` on `/`.
- Compose injects `SPRING_DATASOURCE_URL`, `USERNAME`, and `PASSWORD`. The JDBC host is `postgres` on the Compose network, not `localhost`.
- Flyway is **on**. V1 runs on first boot.

Run the stack **via Compose**. Do not add `5432:5432`. Stop local `npm run dev` before Compose web if 5173 is taken.

```bash
docker compose up --build --wait
curl -fsS http://localhost:8080/api/health
```

First boot can take ~60s. Demo JWT signing key lives in `application.yml` (`meowpay.jwt.secret`) for Step 3; it is not for production.
