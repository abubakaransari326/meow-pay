# MeowPay

A signed-in cat sends treats to another cat. This repo is the API and Postgres ledger. Auth exists only to identify the sender. A web UI is next, not in this pass.

Balance is the sum of ledger rows, not a column on the cat. A send locks the sender (`FOR UPDATE`), then `SUM`s, then either writes debit **−N** and credit **+N** or stores `REJECTED` with no ledger movement. Retrying the same submit (`Idempotency-Key`) does not double-send.

## How to run

You need Docker. Run the API **only via Compose**. Postgres is not published on the host — do not add `5432:5432` or run `mvn spring-boot:run` against localhost.

No `.env` copy. Compose sets `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. The JWT signing key is a demo default in `application.yml`. To override later, set `MEOWPAY_JWT_SECRET` (do not reuse the demo key).

```bash
docker compose up --build --wait
```

API: **http://localhost:8080**. First boot can take ~60s. `--wait` holds until `/api/health` is up.

```bash
curl -fsS http://localhost:8080/api/health
```

Stop with `docker compose down`. `down -v` also wipes the database.

### Demo cats

Password for all three: `treats123`

| Username | Treats |
| --- | --- |
| `luna` | 100 |
| `milo` | 100 |
| `whiskers` | 100 |

### Try it

```bash
# Login (or POST /api/auth/register with a new username)
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"luna","password":"treats123"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -sS http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"

# Send 10 to milo (201). Same key again is 200 and does not move money twice.
curl -sS -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recipientUsername":"milo","amount":10}'

# Not enough treats → 409 INSUFFICIENT_FUNDS (replay of that key is also 409, never 200)
curl -sS -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recipientUsername":"milo","amount":1000}'
```

`GET /api/recipients` lists other cats. `GET /api/me/transfers` is this cat’s history (`REJECTED` is sender-only).

## Tests

From `backend/`, with Docker available (tests start their own Postgres; Compose is not the test DB):

```bash
mvn test
```

## Skipped on purpose

- **No web UI in this pass.** CORS already allows `http://localhost:5173` for the browser later.
- **No funding rail.** Signup is a ledger credit of 100. A later human top-up is the same credit from a funding source.
- **No refresh tokens, email, OAuth, or password reset.** Username + password + a 7-day JWT binds the sender.
- **No hosted deploy.** Clone + Compose is the path. Vercel / Render / Supabase can come later.
- **No locking paper.** The lock is a Postgres `FOR UPDATE` on the sender row, then the SUM.

## How AI was used

A **builder** followed a written `docs/backend/PLAN.md`; a **critic** reviewed the plan and the money path in a separate pass (lock-before-SUM, replay HTTP codes, CORS / `Idempotency-Key` preflight, tests against a committed DB). The decisions are ours to defend.

## API

`Authorization: Bearer <token>` on everything except `/api/auth/**`, `/api/health`, and `OPTIONS`. Errors are `{ "error", "message" }`. Sender is the JWT cat; extra body fields such as `senderUsername` are ignored.

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/auth/register` | `{ username, password }` → `{ token, username }` and a 100-treat credit |
| POST | `/api/auth/login` | same body / response |
| GET | `/api/me` | `{ username, balance }` |
| GET | `/api/recipients` | other cats as `{ username }` |
| POST | `/api/transfers` | header `Idempotency-Key`; body `{ recipientUsername, amount }`. COMPLETED first **201**, COMPLETED replay **200**. Insufficient first and replay **409**. |
| GET | `/api/me/transfers` | this cat’s sends and completed receives |
