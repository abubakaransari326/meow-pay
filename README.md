# MeowPay

**Demo:** [Loom walkthrough](https://www.loom.com/share/f98e416235b942098578cfb4a831fd6a)

A signed-in cat sends treats to another cat. Auth exists only to identify the sender. The web app is a small dashboard (send + history) that stacks on a phone-width viewport.

Balance is the sum of ledger rows, not a column on the cat. A send locks the sender (`FOR UPDATE`), then `SUM`s, then either writes debit **−N** and credit **+N** or stores `REJECTED` with no ledger movement. Retrying the same submit (`Idempotency-Key`) does not double-send.

## How to run

You need Docker. No JDK, no Node, no `.env` copy.

Compose starts Postgres, the API, and the web app. Postgres is not published on the host — do not add `5432:5432`. The JWT signing key is a demo default in `application.yml`. To override later, set `MEOWPAY_JWT_SECRET` (do not reuse the demo key).

```bash
docker compose up --build --wait
```

Then open **http://localhost:5173** (not `127.0.0.1` — CORS allows `localhost` only). First boot can take ~60s. `--wait` holds until the API and the web container are healthy.

API (if you curl): **http://localhost:8080**.

Stop with `docker compose down` (ledger and history stay on the named volume). `down -v` wipes the database.

Local Vite (`cd web && npm run dev`) talks to the same API. Stop Compose `web` first — both bind 5173.

### Demo cats

Password for all three: `treats123`

| Username | Treats |
| --- | --- |
| `luna` | 100 |
| `milo` | 100 |
| `whiskers` | 100 |

### Try it

1. Open **http://localhost:5173**.
2. Log in as `luna` / `treats123` (or register a new cat — they start at 100).
3. Pick `milo`, amount 10, **Send**, then **Confirm**. Balance drops; history shows OUT / Completed.
4. Send 1000. You get a real error (`INSUFFICIENT_FUNDS`). History shows Rejected for luna only. Log in as milo: IN for the 10, no inbound 1000.
5. Refresh: the money is still gone.

The signed-in screen is a dashboard (name, balance, send, history). Below about 640px the cards stack.

JWT lives in `localStorage` (fine for a takehome; not httpOnly cookies). A new Send is a new confirm and a new key.

### Optional curl

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"luna","password":"treats123"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -sS http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

## Tests

Backend only. From `backend/`, with Docker available (tests start their own Postgres; Compose is not the test DB):

```bash
mvn test
```

No frontend test suite.

## Skipped on purpose

- **No funding rail.** Signup is a ledger credit of 100. A later human top-up is the same credit from a funding source.
- **No refresh tokens, email, OAuth, or password reset.** Username + password + a 7-day JWT binds the sender.
- **JWT in `localStorage`.** Not httpOnly cookies.
- **No hosted deploy.** Clone + Compose is the path. Vercel / Render / Supabase can come later.
- **No locking paper.** The lock is a Postgres `FOR UPDATE` on the sender row, then the SUM.
- **No frontend tests.** The money path is covered in `mvn test`.

## How AI was used

A **builder** followed written plans (`docs/backend/PLAN.md`, `docs/frontend/PLAN.md`); a **critic** reviewed the plan and the money path in a separate pass (lock-before-SUM, replay HTTP codes, CORS / `Idempotency-Key` preflight, confirm snapshot, tests against a committed DB). The decisions are ours to defend. Architecture, schema, and why: [docs/DECISIONS.md](docs/DECISIONS.md).

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
