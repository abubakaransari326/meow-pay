# MeowPay — scope

Scope and stack are locked.

**Product one-liner:** a signed-in cat sends treats to another cat, end to end, on the web, against a real backend with real persistence.

**Time posture:** the brief’s ~4 hours is a soft cap. Extra time goes into the **backend**: ledger correctness, idempotent sends, tests. Auth exists only to identify the signed-in cat. The UI should be clear, not impressive.

## What a reviewer should be able to do

From a fresh clone, following the README:

1. Start the stack.
2. Open the web app (not a mobile app).
3. Register a new cat **or** log in as a seeded demo cat.
4. See their own balance (new cats start with **100 treats**).
5. Pick a **recipient**, enter an amount, send. The sender is always the signed-in cat — never chosen in the UI.
6. See their balance drop and the transfer in their history.
7. Try a failing case (insufficient treats) and see a real error, not a crash.

That *is* the slice. Everything below is in service of that path.

## In — must ship

The transfer flow, fully vertical, with just enough identity to make “you are the sender” true:

| Layer | What |
| --- | --- |
| **Web UI** | Register / log in; then a send screen as *this* cat: own balance, pick recipient, amount, confirm, success/failure, recent transfers. No sender picker. |
| **Simple auth** | Register + login with **username + password**. Username is the identifier (no email). **Bearer JWT** so the API knows who is sending. Auth is not a showcase — just enough to bind the sender. No OAuth, email, password reset, refresh tokens, or KYC. |
| **Signup bonus** | Creating a cat credits **100 treats** in the same way a future top-up would: a ledger credit, not a magic default column. README says: human top-up is the same credit, from a funding source instead of “signup bonus.” |
| **API** | Auth; create a transfer **as the current user**; read own wallet/balance; list recipients (other cats); read own transfer history. |
| **Persistence** | Real database. Treats actually move. Restarting the app does not invent the money back. Passwords stored hashed, never in plaintext. |
| **Money rules** | Amount > 0; sender ≠ recipient; sender has enough treats; the debit and credit happen together or not at all. Sender is taken from the JWT, not from the request body (body cannot impersonate another cat). |
| **Demo data** | 2–3 seeded cats with documented passwords in the README, so a reviewer can send immediately without registering twice. They also start with 100 treats. |
| **Run story** | README that runs from a clean clone. Prefer one command (e.g. Compose) over a long local-tooling list. |
| **Ledger** | Source of truth for money. A transfer writes a debit and a credit; signup bonus is a single credit. Balance is the sum of entries (or a cached balance updated in the same transaction). Not two integers mutated in place. |
| **Idempotent send** | Retrying the same submit does not double-send. |
| **Tests** | **Backend only.** Money path: happy path, insufficient funds, invalid amount / same-cat, cannot send as someone else, signup bonus of 100, idempotent retry. No frontend test suite. |

**Where we impress:** ledger + idempotency + backend tests. Not auth, not UI chrome.

## Polish — extra time, still the same slice

Only after the path above works. Cut from the bottom if time runs out.

1. **Transfer states** — at least `completed` and `rejected` (or equivalent), visible in the UI.
2. **History that is useful** — who, amount, when, status; for the signed-in cat.
3. **UI that is usable** — login/register, one clear send screen, readable balance, empty/error/success states. Stop when it is obvious, not when it is pretty.
4. **Obvious “what we skipped” in the README** — written as decisions, not apologies. Especially: top-up is the same ledger credit as signup bonus; we did not build a funding rail.

## Out — not this takehome

| Cut | Why |
| --- | --- |
| **Mobile / Flutter** | You want a web app. Their Flutter stack is a plus, not a requirement. One UI, done well. |
| **Human top-up / funding rails** | Signup bonus *is* the funding event for the demo. Same ledger credit; swap the source later. Do not build a top-up screen. |
| **Sender picker / impersonation** | The signed-in cat is the sender. Picking both sides is a demo harness, not a product. |
| **Real auth platform** | OAuth, magic links, **email**, password reset, 2FA, KYC — out. Username + password is enough to identify the sender. |
| **Frontend tests** | Backend tests cover the money. No Cypress/Playwright suite. |
| **Concurrent transfer / locking deep-dive** | Worth a README note and a sane transaction boundary. Not a distributed-systems paper. |
| **FX, multi-currency, fees, chargebacks** | Treats are one currency. |
| **Notifications, emails, websockets** | Refresh/refetch after send is enough. |
| **Admin, analytics, settings** | Not the slice. |
| **Pixel-perfect / design-system UI** | Clear and usable beats pretty-and-empty. |

## Cross-cutting bar

- Backend is **not** a mock behind the UI.
- No secrets, live keys, or customer-like dumps in the repo.
- Commit as we go (submission rule). Do not squash the story away.
- README covers: what we built, how to run, what we skipped and why, how AI was used.

## Stack (locked)

One repo: `backend/` (Spring Boot) and `web/` (Vite React), plus Compose at the root.

| Piece | Choice | Why |
| --- | --- | --- |
| **Backend** | **Spring Boot**, **Java 21** | Their stack family. Real service, transactions for the ledger. Kotlin skipped: Java is what we ship fastest. |
| **Frontend** | **React + TypeScript** on **Vite** | Web app, as scoped. SPA talking to Spring; no Next.js SSR/App Router. |
| **DB** | **Postgres** | Real persistence, ACID transfers. |
| **Local run** | **Docker Compose** | Postgres + API + web from a clean clone. Submission still stands if the live demo is down. |
| **Migrations** | **Flyway** | Schema in git, not `ddl-auto`. |
| **Auth** | **Username + password**, **Bearer JWT** (HS256, long-lived for demo, no refresh tokens, no email) | Username identifies the cat. JWT is how the API knows the sender across Vercel vs API origin. Auth is not a showcase. |

**Not using:** Flutter/mobile, Next.js, Kotlin, session cookies, Supabase Auth, calling Supabase from the browser.

### Deploy (demo URL, not the submission)

The GitHub clone + Compose is what they grade. The live app is so they can click it.

| Piece | Host | Notes |
| --- | --- | --- |
| **Web** | **Vercel** | Vite build. `VITE_API_URL` points at the API. |
| **API** | **Render** (Docker) | Spring Boot. Swap to Railway if you prefer; do not put Java on Vercel. |
| **DB** | **Supabase** | Hosted Postgres only. Direct (or session-mode) connection — not transaction-mode PgBouncer. No Supabase client in the FE. |

Secrets (JWT signing key, DB URL, etc.) live in Vercel/Render/Supabase env vars, never in git. CORS allows the Vercel origin. README should warn that a sleeping free-tier API may take ~30–60s on first request.
