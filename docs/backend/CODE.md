# Backend code

What the API code is and how the pieces connect. Filled in as each step lands.

## Layout

- `backend/` is a Maven module (Spring Boot **3.4.5**, Java **21**).
- Entry point: `com.meowpay.MeowPayApplication`. It excludes `UserDetailsServiceAutoConfiguration` so Boot does not invent a default user.

## What exists now

- `GET /api/health` → `{ "status": "up" }` (`HealthController`). Does not touch the database.
- Security is **STATELESS**, CSRF/form/basic off. Open: `OPTIONS /**`, `/api/auth/**`, `/api/health`. Everything else needs a Bearer JWT.
- CORS is a **global** `CorsConfigurationSource` (origin `http://localhost:5173` only; headers `Authorization`, `Content-Type`, `Idempotency-Key`). Preflight works before `/api/transfers` exists.
- `JwtService` issues/parses HS256 tokens; `sub` is the cat UUID. `JwtAuthFilter` does not look up cats yet (no table). Invalid/missing token on a protected route → `{ "error": "UNAUTHORIZED" }`.
- Errors: `{ "error", "message" }`. Bind / unreadable JSON / missing header → `VALIDATION`. Uncaught → `INTERNAL` with no SQL in `message`. Do not log password or `Authorization`.
- Jackson `accept-float-as-int` is **false**. Hibernate JDBC timezone is UTC.
- `POST /api/auth/register` → **201** `{ token, username }`. Username is trimmed, then rejected if empty/`> 64`, then lowercased. Password empty/`> 72` → `VALIDATION`. One inner TX writes the cat + `SIGNUP_BONUS` **+100**. The outer method is not `@Transactional`; a unique-name race becomes `USERNAME_TAKEN` (409), not 500.
- `POST /api/auth/login` → **200** same body. Unknown user and wrong password both return 401 `UNAUTHORIZED` with the same message. JWT `sub` is the cat id.

## Schema (Flyway `V1__init.sql`)

Flyway is **on**. Three tables, no balance column on `cats`.

- `cats` — id, lowercase username, BCrypt hash, `created_at`.
- `transfers` — sender/recipient, positive `amount`, `COMPLETED`|`REJECTED`, `idempotency_key NOT NULL`, `UNIQUE (sender_id, idempotency_key)`.
- `ledger_entries` — signed `amount` (`−N` debit, `+N` credit/signup), type, `transfer_id` NULL only for `SIGNUP_BONUS`. CHECKs enforce sign and type vs `transfer_id`. Partial `UNIQUE (transfer_id, type)` stops two credits on one transfer.

Balance of a cat is `COALESCE(SUM(ledger_entries.amount), 0)` (`LedgerEntryRepository.sumBalance`). Sender lock is `CatRepository.lockById` (`FOR UPDATE`). Debit is negative; `type` is metadata only.
- Datasource username/password default to the demo role `meowpay` / `meowpay`. There is **no** JDBC URL in `application.yml` — Compose sets `SPRING_DATASOURCE_URL` (and user/password). We do not default to `localhost:5432`.

## How it runs (Compose)

Root `docker-compose.yml` has two services: `postgres` and `api`. There is no web service and Postgres **5432 is not published** on the host.

- Postgres: `postgres:16-alpine`, db/user/password `meowpay`, healthcheck `pg_isready`.
- API: built from `backend/Dockerfile` (Maven package, then `eclipse-temurin:21-jre` **with curl installed** — the JRE image does not ship curl). Healthcheck hits `/api/health`, `start_period` 60s.
- Compose injects `SPRING_DATASOURCE_URL`, `USERNAME`, and `PASSWORD`. The JDBC host is `postgres` on the Compose network, not `localhost`.
- Flyway is still off. The API starts, Hikari connects, no migrations run.

Run the API **only via Compose**. Do not add `5432:5432` so host Maven can reach the DB.

```bash
docker compose up --build --wait
curl -fsS http://localhost:8080/api/health
```

First boot can take ~60s. Demo JWT signing key lives in `application.yml` (`meowpay.jwt.secret`) for Step 3; it is not for production.
