# Frontend code

What the web app is and how it talks to the API. Filled in as each step lands.

The shipped API is [docs/backend/CONTRACT.md](../backend/CONTRACT.md). Do not invent fields or treat only HTTP 201 as a successful send.

## Layout

- `web/` is a Vite **6** + React **19** + TypeScript (strict) SPA. `engines.node` is **>=22**.
- Dev server is **http://localhost:5173** (`strictPort: true`). There is **no Vite proxy** — CORS must work as Compose will run it (browser → `localhost:8080`). Do not open `127.0.0.1:5173`.
- Look tokens live in `src/styles.css` (cream / ginger / sage / clay, Nunito with system sans fallback). Placeholder page only; screens come later.
- `src/api.ts` talks to `VITE_API_URL` (default `http://localhost:8080`). Types match [CONTRACT.md](../backend/CONTRACT.md). `Content-Type` is set on POST only. Login/register `401` is an `ApiError` (`UNAUTHORIZED`). Any other `401` clears `meowpay.token` and throws `UnauthorizedError`. HTTP 200 and 201 with no `error` field are success. TypeError retry is inside `sendTreats` only.
- `AuthPage` is login / register. Submit uses a synchronous `inFlight` ref, then `setBusy`. Token key is `meowpay.token`. A TypeError shows the Compose-wait copy, not the session-expired path. After sign-in, `SendStub` is a one-line Sign out (wallet is Step 4).
- `npm run build` is `tsc --noEmit` then `vite build`. The lockfile is for Compose `npm ci` later.
