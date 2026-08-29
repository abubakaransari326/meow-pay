# Frontend code

What the web app is and how it talks to the API. Filled in as each step lands.

The shipped API is [docs/backend/CONTRACT.md](../backend/CONTRACT.md). Do not invent fields or treat only HTTP 201 as a successful send.

## Layout

- `web/` is a Vite **6** + React **19** + TypeScript (strict) SPA. `engines.node` is **>=22**.
- Dev server is **http://localhost:5173** (`strictPort: true`). There is **no Vite proxy** — CORS must work as Compose will run it (browser → `localhost:8080`). Do not open `127.0.0.1:5173`.
- Look tokens live in `src/styles.css` (cream / ginger / sage / clay, Nunito with system sans fallback). Auth uses `.page--auth` (~28rem). Signed-in uses `.dash` (max 64rem, header + `.dash-grid`; two columns from 640px). No sidebar.
- `src/api.ts` talks to `VITE_API_URL` (default `http://localhost:8080`). Types match [CONTRACT.md](../backend/CONTRACT.md). `Content-Type` is set on POST only. Login/register `401` is an `ApiError` (`UNAUTHORIZED`). Any other `401` clears `meowpay.token` and throws `UnauthorizedError`. HTTP 200 and 201 with no `error` field are success. TypeError retry is inside `sendTreats` only.
- `AuthPage` is login / register on the narrow card. Submit uses a synchronous `inFlight` ref, then `setBusy`. Token key is `meowpay.token`. A TypeError shows the Compose-wait copy, not the session-expired path.
- `SendPage` loads `/api/me`, `/api/recipients`, and `/api/me/transfers` in parallel. Header is this username, balance, Sign out. Recipient `<select>` never includes self. Send snapshots `{ recipientUsername, amount }` and disables those fields; Confirm POSTs that pair with a new UUID. `inFlight` is a ref; on 200/201 the snapshot is cleared **before** the ref. After a completed send or `INSUFFICIENT_FUNDS`, refetch me + history. History compares `IN`/`OUT` and `COMPLETED`/`REJECTED`; OUT ginger, IN sage, REJECTED clay.
- `npm run build` is `tsc --noEmit` then `vite build`. Compose `web` uses `npm ci` from the lockfile, then nginx. `nginx.conf` overwrites `/etc/nginx/conf.d/default.conf`. The image bakes `VITE_API_URL=http://localhost:8080` (browser URL). `docker compose --wait` waits on the web `wget` healthcheck. Local Vite and Compose web cannot both bind 5173.
