# Frontend-only implementation plan (critic review)

**Status:** plan only. Do not implement until the human approves a step. The human decides when to commit.

## Frontend steps

- [x] 0 — Repo hygiene (gitignore for `web/`, this checklist, CODE.md stub)
- [x] 1 — Vite + React + TypeScript skeleton on port 5173
- [x] 2 — API client (contract types, `error` switch, 401 split)
- [x] 3 — Auth screen (register / login, JWT in `localStorage`)
- [ ] 4 — Send-screen shell (`/api/me`, `/api/recipients`, no POST)
- [ ] 5 — Confirm + send treats (ref guard, new key per confirm, 200/201)
- [ ] 6 — History (`/api/me/transfers`, IN / OUT / REJECTED)
- [ ] 7 — Compose `web` service (nginx, baked `VITE_API_URL`)
- [ ] 8 — README: open `http://localhost:5173`

**Review this file** with `@critic`. The shipped API is [docs/backend/CONTRACT.md](../backend/CONTRACT.md). Product rules also live in [PLAN.md](../../PLAN.md) Phases 6–7. This file is the **build script**.

**In this pass:** `web/` + Compose `web` service + README open-and-click. **Not in this pass:** hosted deploy, frontend test suite, top-up UI, refresh tokens, React Router, Next.js.

**How we work:** implement **one approved step at a time**. Update [CODE.md](CODE.md) in the same step. Do not update README until Step 8. Do not commit unless the human asks.

**Reference (do not copy blindly):** `/Users/abubakar/Downloads/meow-pay-implemented/web` is an earlier snapshot. Rebuild clean. One snapshot bug to **not** repeat: login `401` was thrown as “session dead / network down” instead of showing `UNAUTHORIZED`.

**Backend is done.** API + tests + Compose `postgres` + `api` already run. During Steps 1–6, `npm run dev` talks to that API at `http://localhost:8080`. Step 7 is the one-command web container.

---

## Goal

A reviewer runs Compose, opens **`http://localhost:5173`**, signs in as `luna` (or registers), sees **100** treats, picks a recipient, sends, watches balance and history update, and sees a real error on insufficient funds — not a crash. The signed-in cat is always the sender. Auth is only there to bind that cat.

Clear and usable. Not pretty. No Cypress/Playwright.

---

## Look (one stylesheet, not a design system)

Orange-tabby / cream, so it reads as cats-and-treats without chrome. Land the tokens in Step 1 (`web/src/styles.css`) and reuse them. No dark mode, no component library, no extra webfont files in the image. Optional Google Fonts link for Nunito; **`font-family` must include a system sans fallback** (`ui-sans-serif, system-ui, sans-serif`) so a reviewer without that network path still gets a readable UI.

| Token | Value | Use |
| --- | --- | --- |
| Page | `#fff6eb` cream | background |
| Ink | `#3b2416` brown | text |
| Ginger | `#e07a3d` | primary button, balance, history **OUT** |
| Sage | `#3d7a5a` | success, history **IN** |
| Clay | `#c45c4a` | errors, history **REJECTED** |
| Card | `#fffdf8` | forms / history |
| Type | Nunito, then system sans | UI + headings |

Wide enough line-height, one column ~28rem, no cat clip-art or emoji-as-layout. A “treats” word is the theme; we do not draw a mascot.

---

## Locked UI rules (do not reopen)

These are the FE side of the contract. Do not “simplify” them during a step.

1. **Two screens, no router.** Token present → send screen. No token → auth screen (login / register toggle).
2. **JWT in `localStorage`** (key `meowpay.token`). README will note this vs httpOnly. Sign out and protected-route `401` clear it.
3. **`401` split.** `/api/auth/login` and `/api/auth/register` → show `error` / `message` on the form. Any other `401` → clear token, show auth. A failed login is not a network failure and not “session expired.”
4. **Branch on `error`**, not status alone. Prefer the server `message`. Known codes: `VALIDATION`, `SAME_CAT`, `UNAUTHORIZED`, `NOT_FOUND`, `USERNAME_TAKEN`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_CONFLICT`, `INTERNAL`.
5. **Send succeeds on HTTP 200 or 201** when the body is a transfer and has no `error` field. Replay of a completed send is 200.
6. **Confirm, then send.** First submit does **not** POST. Require a selected recipient (empty `<select>` does not enter confirm). Snapshot `{ recipientUsername, amount }` and **disable those fields** until Cancel or success. Banner is “Send 10 to milo?” from that pair. Confirm POSTs **that pair only** — not the live `<select>` / amount. New confirm = new `crypto.randomUUID()`. Only a transport retry of that same in-flight send reuses the key.
7. **In-flight is a ref, not only `useState`.** Same guard on **auth submit** and **Confirm**. `setBusy(true)` paints too late. At the top of the handler: if `inFlight.current` return; set `inFlight.current = true` **synchronously**; then `setBusy` and disable. Do not rely on React state alone.
8. **Do not clear `inFlight` while Confirm is still on screen after a 2xx.** A `finally` that sets the ref false lets a second click mint a new key before React unmounts Confirm (refetch of `/api/me` can be slow). On 200/201: drop the snapshot (Confirm unmounts) **then** clear the ref — or leave `inFlight` true until confirm is gone (Cancel / success). On error: Confirm may stay; then clear the ref so they can Cancel.
9. **No sender picker.** Body is `{ recipientUsername, amount }`. Do not send `senderUsername`.
10. **Recipient is a `<select>`** of `GET /api/recipients`. Never include the signed-in cat. Empty list → disable Send, show “No other cats yet.” Empty value `""` must not enter confirm (and must not POST).
11. **Amount is a positive integer** on the client before POST (`Number.isInteger`, `> 0`). Do not send `10.5`.
12. **After a completed send:** refetch `GET /api/me` and (once Step 6 exists) `GET /api/me/transfers`. After `INSUFFICIENT_FUNDS`: show the message, refetch me + history so the sender sees `REJECTED` / `OUT`.
13. **History:** compare `direction` / `status` to the **contract strings** (`IN` / `OUT`, `COMPLETED` / `REJECTED`). Display can be human (“Rejected”). Color OUT ginger, IN sage, REJECTED clay. Empty state when `[]`. Recipients never see another cat’s rejected send (API already filters).
14. **Browser origin is `http://localhost:5173`.** Not `127.0.0.1` (CORS). `VITE_API_URL=http://localhost:8080` in the **browser**. Never `http://api:8080`. No Vite proxy that hides a CORS mistake.
15. **No frontend tests.** Manual / browser path only (SCOPE). Backend already covers the money.
16. **No new API.** If the UI wants a field the contract does not have, the UI is wrong.
17. **TypeError retry lives only in `sendTreats`.** Never wrap register/login (or GET) in a generic retry. A dropped 201 on register + retry is `USERNAME_TAKEN` and no stored token.

---

## Approach

Thin SPA. `fetch` + React state. No axios, no React Query, no router, no cookies, no Supabase client.

```
AuthPage  ↔  localStorage token  ↔  SendPage
                │
                └─ api.ts  →  http://localhost:8080/api/…
```

Compose today: `postgres` + `api` on **8080**. After Step 7: add `web` on host **5173** (container :80). API stays 8080. Postgres stays unpublished.

Dev loop for Steps 1–6: API via Compose, UI via `npm run dev` on 5173 (CORS already allows that origin).

---

## Steps

### Step 0 — Repo hygiene (docs / gitignore only)

**Build:** Expand [`.gitignore`](../../.gitignore) with `web/node_modules/`, `web/dist/`. [CODE.md](CODE.md) already stubbed with this plan. Tick this box when gitignore lands.

**Test:** `web/node_modules/` is ignored. This file has the checklist. CODE.md stub exists.

**Docs:** CODE.md stub only. **Do not** change README. **Do not** commit.

---

### Step 1 — Vite + React + TypeScript skeleton

**Build:** `web/` with Vite **6**, React **19**, TypeScript (strict), engines / image **Node 22**. `vite.config.ts`: port **5173**, `strictPort: true`, no proxy. `index.html` + a placeholder “MeowPay” page so the origin exists. `styles.css` with the Look tokens (cream / ginger / Nunito + system sans fallback). Scripts: `dev`, `build` (`tsc --noEmit` then vite). Write `package-lock.json`. Compose will use **`npm ci`**, not `npm install`.

**Test:** `cd web && npm run dev` → **http://localhost:5173** shows the placeholder. Opening `127.0.0.1:5173` is not the supported path (do not “fix” CORS for it).

**Docs:** CODE.md: Vite app, port, why no proxy. **Do not** change README.

---

### Step 2 — API client

**Build:** [`web/src/api.ts`](../../web/src/api.ts) (name can match). Types copied from [CONTRACT.md](../backend/CONTRACT.md): `AuthResponse`, `MeResponse`, `Recipient`, `TransferResponse`, `HistoryItem`, `ApiError`.

`VITE_API_URL` default `http://localhost:8080`. `fetch` wrapper:

- Set `Content-Type: application/json` **on POST only** (GET with that header forces a CORS preflight). Attach Bearer if a token exists. Attach `Idempotency-Key` only when the caller passes one.
- Parse JSON. If the body has `error`, throw it as `ApiError` (except the 401 split below).
- **`401` on `/api/auth/login` or `/api/auth/register`:** throw `ApiError` (`UNAUTHORIZED`). Do **not** clear a token that is not the problem; do **not** use the session-expired path.
- **`401` on any other route:** clear token, throw a distinct `UnauthorizedError` so the app returns to login.
- **2xx and no `error` field:** return the body. 200 and 201 are both success.
- Non-JSON / empty error: do not crash; surface a generic `VALIDATION` or `INTERNAL` message.
- The shared `request()` helper does **not** retry. **`sendTreats` only** retries once on `TypeError`, same `Idempotency-Key`. Register/login/GET must not retry.

Helpers: `getToken` / `setToken`, `register`, `login`, `fetchMe`, `fetchRecipients`, `fetchHistory`, `sendTreats` (retry lives here), `messageFor(error)` (server `message` first, then a short fallback per code).

**Test:** Against the running Compose API, from the browser console or a temporary button is enough: login as luna returns a token; bad password shows `UNAUTHORIZED` text, not “could not reach the API”; `GET /api/me` with a junk token clears storage.

**Docs:** CODE.md: client rules, 401 split, 200/201. **Do not** change README.

---

### Step 3 — Auth screen

**Build:** Login / register toggle. Username + password. Same **`inFlight` ref** as send (rule 7): double-click Register must not fire two POSTs. Busy disables the button. On 201/200: `setToken`, go to send stub. Show `messageFor` on `ApiError`. Network `TypeError`: “Could not reach the API. If you just started Compose, wait until it is up.”

Send stub in this step includes a one-line **Sign out** (clear `meowpay.token`, show auth). Do not wait for Step 4 for that control.

**Test (browser):** Register a new cat → lands signed in. Sign out. Log in as `luna` / `treats123`. Wrong password shows the API message. Duplicate register shows `USERNAME_TAKEN`. Double-click Register: one 201, not a following 409 on the form.

**Docs:** CODE.md: auth screen, token key, auth ref guard, Sign out. **Do not** change README.

---

### Step 4 — Send-screen shell

**Build:** If token present, load `GET /api/me` and `GET /api/recipients` in parallel. Show **this** username and balance. Recipient `<select>`. Amount input (not submitted yet). Keep the Step 3 Sign out (header is fine). Loading and “API not up” empty/error states. Protected `401` → auth. **No** `POST /api/transfers` yet. **No** sender field.

**Test (browser):** Luna sees 100 (or current ledger balance) and milo + whiskers (not luna). A brand-new cat sees the three demo cats, not themselves.

**Docs:** CODE.md: send shell. **Do not** change README.

---

### Step 5 — Confirm + send treats

**Build:** First submit: require a selected recipient and a positive integer amount. Store snapshot `{ recipientUsername, amount }`, disable those fields, show “Send 10 to milo?” from the **snapshot** + Confirm / Cancel. No POST yet. Cancel drops the snapshot and re-enables the fields.

Confirm handler POSTs **the snapshot only** (not live inputs):

1. If `inFlight.current` → return.
2. `inFlight.current = true`, then `setBusy(true)`, disable Confirm.
3. `crypto.randomUUID()` → `POST /api/transfers` with the snapshotted pair.
4. On **200/201:** drop the snapshot (Confirm unmounts) **then** `inFlight.current = false` and `setBusy(false)`. Do **not** clear the ref in a `finally` while Confirm is still mounted after success.
5. On error: keep or drop confirm as you like; clear the ref so they can Cancel. Banner from `messageFor`; on `INSUFFICIENT_FUNDS` refetch me (history refetch waits for Step 6).

Disable Send/Confirm while `busy` or recipients empty. Not a modal library.

**Test (browser):** Luna fills 10 to milo, first click only shows confirm (no POST). Change amount to 90 while confirming — fields are disabled; Confirm still POSTs **10 / milo**. Confirm → 201, Confirm is gone before a second click can fire; luna **90** not 80. Send 1000 → confirm → `INSUFFICIENT_FUNDS`, no crash. Hard-refresh: balance stays (ledger).

**Docs:** CODE.md: snapshot, dismiss-before-ref-clear, key-per-confirm, 200/201. **Do not** change README.

---

### Step 6 — History

**Build:** Load `GET /api/me/transfers` with me/recipients. Compare `row.direction === "IN" | "OUT"` and `row.status === "COMPLETED" | "REJECTED"` (uppercase, as the API sends). Display can be “From”/“To” and “Completed”/“Rejected”. Color OUT ginger, IN sage, REJECTED clay. Newest first. Empty: “No transfers yet.” After completed send **and** after insufficient, refetch history. Luna’s failed 1000 is `OUT` / `REJECTED`. Milo does not see that reject. Whiskers (or milo after a completed receive) sees `IN` / `COMPLETED`.

**Test (browser):** After Step 5 leftovers or a fresh send: sender sees OUT; recipient (other browser / other login) sees IN; overspend appears only on the sender.

**Docs:** CODE.md: history fields and refetch. **Do not** change README.

---

### Step 7 — Compose web

**Build:** [`web/Dockerfile`](../../web/Dockerfile): `FROM node:22-alpine` for build. Copy `package.json` **and** `package-lock.json`, then **`npm ci`** (not `npm install`). Build-arg `VITE_API_URL=http://localhost:8080`. Copy `dist` into `nginx:1.27-alpine`. Copy [`web/nginx.conf`](../../web/nginx.conf) over **`/etc/nginx/conf.d/default.conf`** (otherwise stock nginx is what listens). `try_files` → `index.html` (SPA). Do **not** proxy `/api` to `http://api:8080` — the browser calls localhost:8080. [`web/.dockerignore`](../../web/.dockerignore): `node_modules`, `dist`.

Root [`docker-compose.yml`](../../docker-compose.yml): `web` service, `5173:80`, `depends_on: api: condition: service_healthy`, build arg `VITE_API_URL=http://localhost:8080`. **HEALTHCHECK** on web: `wget -qO- http://127.0.0.1:80/` (nginx alpine ships wget) so `docker compose --wait` does not return before port 80 listens. Do not publish 5432. Do not add a host `.env` requirement.

**Test:** `docker compose up --build --wait` returns only after web is healthy → open **http://localhost:5173** (not 127.0.0.1) → login luna → confirm → send. `npm run dev` still works against the same API (`docker compose stop web` first if 5173 is taken).

**Docs:** FE CODE.md: image, `npm ci`, healthcheck, why the baked URL is localhost. Update [docs/backend/CODE.md](../backend/CODE.md) Compose paragraph: three services (`postgres`, `api`, `web`); drop “there is no web service.” **Do not** change README yet (next step).

---

### Step 8 — README how-to-run (web)

**Build:** Update [README.md](../../README.md): one command starts Postgres + API + web; open `http://localhost:5173`; wait for first API boot; demo cats; send / overspend on the page; optional `cd web && npm run dev` for FE work; CORS is `localhost` only; skipped (funding rail, refresh tokens, httpOnly, hosted deploy, FE tests); lock is still `FOR UPDATE` (backend). Keep curl as optional. No `.env` copy.

**Test:** `docker compose down -v && docker compose up --build --wait`, follow README, complete the reviewer path (login, send, insufficient).

**Docs:** CODE.md: README is the runbook. Tick Step 8.

---

## Files we expect to touch

| Path | Why |
| --- | --- |
| `docs/frontend/PLAN.md` | this plan |
| `docs/frontend/CODE.md` | explain the UI as steps land |
| `.gitignore` | `web/node_modules/`, `web/dist/` |
| `web/package.json`, lockfile, `tsconfig*`, `vite.config.ts` | Vite app |
| `web/index.html`, `web/src/*` | auth, send, history, `api.ts`, styles |
| `web/Dockerfile`, `nginx.conf`, `.dockerignore` | Compose web |
| `docker-compose.yml` | add `web` |
| `README.md` | Step 8 only |

Do **not** change backend Java/SQL unless the critic finds a real contract bug (then stop and say so). Do not put product code under `docs/agents/` or `.cursor/`.

---

## Decisions (closed)

1. **No React Router.** Token in memory + `localStorage` picks the screen.
2. **No Vite proxy.** CORS must work as the reviewer will run it (Compose web → API on 8080).
3. **React 19 + Vite 6 + TypeScript.** Fast and current; no Next.js.
4. **`fetch` only.** TypeError retry is inside `sendTreats` only — not a generic `request()` wrapper.
5. **JWT in `localStorage`.** SCOPE / root plan already closed this vs httpOnly.
6. **History on the send screen**, not a second route.
7. **Hosted deploy out.** Same as backend.
8. **No FE tests.** Manual browser path. Backend `mvn test` stays the money proof.
9. **Snapshot login-401 bug is out of bounds.** Auth 401 ≠ protected 401.
10. **One cream / ginger theme.** Tokens in the Look table. No dark mode, no UI kit.

---

## Risks / unknowns

- **Compose web vs `npm run dev` both on 5173:** cannot bind twice. Step 7 test uses Compose. Local Vite needs Compose **web** stopped (`docker compose stop web` or run only `postgres`+`api`). Say this in CODE.md / README. Not a product unknown.
- **Stale volume balances:** a reviewer who already sent as luna will not see 100. README: `down -v` for a clean 100, or just send a smaller amount. Same as backend.
- **No FE unit test for “200 is success”:** we will not add Vitest unless a human asks. The client helper is small; the critic should read it. Flagged on purpose.

---

## How we will test

**Not doing:** Cypress, Playwright, Jest/Vitest (unless a human asks), mobile.

**Each step:** browser against the real API (user rule). Not a screenshot-only check.

**Reviewer path (after Step 8)**

1. `docker compose up --build --wait` — no `.env`, no JDK/Node required.
2. Open **http://localhost:5173**.
3. Log in as `luna` / `treats123` (or register).
4. See own name + balance. Pick `milo`, amount 10, Send → confirm copy matches, **Confirm** POSTs that pair. Balance drops; history shows OUT / COMPLETED (ginger).
5. Send 1000. Error from `INSUFFICIENT_FUNDS`. History shows REJECTED for luna. Log in as milo: IN for the 10, no inbound 1000.
6. Refresh: money still gone. Second click after success is a **new** send (new key), not a silent replay.

---

## Suggested commit story (do not squash)

1. Vite skeleton on 5173.
2. API client + auth.
3. Send + history.
4. Compose web + README.

Or fewer commits if the human batches. They decide.

---

## Critic findings — builder response

| # | Verdict | What changed |
| --- | --- | --- |
| CODE JwtAuthFilter “no table” | **Fixed** | [docs/backend/CODE.md](../backend/CODE.md): filter parses `sub` only; “no table” dropped. |
| CODE “no web service” | **Fixed** | Still true today. Step 7 docs: rewrite that paragraph when `web` lands. |
| 1 Double-click / `useState(busy)` | **Fixed** | Rule 7 + Step 5: synchronous `inFlight` ref, then `setBusy` + disable. Test: one POST, luna 90. |
| 2 Confirm missing | **Fixed** | Rule 6 + Step 5: same-screen “Send 10 to milo?” then Confirm. First submit does not POST. |
| 3 TypeError retry on register | **Fixed** | Rule 16 + Step 2: retry only inside `sendTreats`. |
| 4 Web `--wait` / no healthcheck | **Fixed** | Step 7: `wget` HEALTHCHECK on nginx so `--wait` waits for port 80. |
| 5 Floating Node / `npm install` | **Fixed** | Step 1 lockfile; Step 7 `node:22-alpine` + `npm ci`. |
| 6 `status === "completed"` | **Fixed** | Rule 12 + Step 6: compare `IN`/`OUT`, `COMPLETED`/`REJECTED`. |
| Nit Content-Type on GET | **Fixed** | Step 2: `Content-Type` on POST only. |
| Nit history colors unused | **Fixed** | Look table + Step 6: OUT ginger, IN sage, REJECTED clay. |
| Nit Nunito offline | **Fixed** | System sans fallback required. |
| Nit server-only field lengths | **Won’t fix** | `VALIDATION` + `message` is enough. |
| Confirm live inputs vs banner | **Fixed** | Rule 6 + Step 5: snapshot pair, disable fields, Confirm POSTs the snapshot. |
| `finally` releases Confirm after 201 | **Fixed** | Rule 8: unmount Confirm before clearing `inFlight`. |
| Step 3 test Sign out | **Fixed** | Step 3 ships a one-line Sign out. |
| Auth no ref guard | **Fixed** | Rule 7 + Step 3: same `inFlight` on register/login. |
| Nit empty recipient | **Fixed** | Rule 6 / 10: no confirm, no POST. |
| Nit nginx.conf path | **Fixed** | Step 7: copy onto `/etc/nginx/conf.d/default.conf`. |

---

## Stop line

**Do not implement** until the human explicitly approves a step. A finished plan is not approval.
