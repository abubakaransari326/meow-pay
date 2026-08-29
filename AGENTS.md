# Project

meow-pay is a fullstack takehome (see `meowpay-fullstack-takehome.pdf` and `SCOPE.md`). One repo: `backend/` (Spring Boot, Java 21) and `web/` (Vite + React + TypeScript), Postgres, Flyway, Bearer JWT (username + password; username is the identifier). Local run is Docker Compose. Demo deploy: Vercel (web), Render (API), Supabase (Postgres only — no Supabase Auth). Impress on ledger, idempotent transfers, and backend tests — not on auth or UI chrome.

## Builder / Critic workflow

This repo uses a two-role review process — full detail in
`docs/agents/ROLES.md`.

Default role for a plain session: **builder**, unless you're told
otherwise or the human invokes `@critic` / the critic skill. When acting
as critic, follow `docs/agents/CRITIC.md` and do not edit files.

Invoke roles in Cursor with `@builder` or `@critic` (project rules), or by
asking this agent to act as builder or critic (project skills under
`.cursor/skills/`).

## Non-negotiable

`docs/agents/SENSITIVE-DATA-POLICY.md`

**No code without explicit approval.** Plan, review, and discuss freely. Do not
write or edit product code (app, tests, Compose, shipping config) until the
human clearly says to implement that step. A finished plan is not approval.
