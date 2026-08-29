# Builder / Critic workflow

This repo is worked on by two roles, not two tools:

- **BUILDER** — plans, then writes code. See `BUILDER.md`.
- **CRITIC** — reviews the plan and the code, and tries to find faults. See
  `CRITIC.md`. The critic never edits code — only reports findings.

Either Claude Code or Cursor can be assigned either role, for any given
task. Nothing in `BUILDER.md` or `CRITIC.md` is tool-specific. Pick whichever
pairing makes sense for a task, and feel free to swap: same tool playing
the same role every time will eventually share its own blind spots with
itself, so periodically flipping which tool builds and which one critiques
(e.g. alternate weekly, or alternate per feature) is worth doing on
purpose, not just when convenient.

## Why a separate critic session, not the same chat

A model that just spent a long context planning and writing something is a
weaker critic of that same thing — it's primed to defend its own choices.
Whenever practical, run the critic in a **fresh session** that only sees:

- the plan (`PLAN.md`) and/or the diff, and
- the relevant parts of the codebase it needs to check the claim against

— not the builder's reasoning trail. If you're using two separate CLI
tools (Claude Code + Cursor) this happens naturally. If you're using two
Claude Code sessions, start the critic one from a clean `claude` (not a
continued conversation).

## The loop

1. **Plan.** For anything beyond a trivial fix, the builder writes a short
   `PLAN.md` in the repo root (or the relevant package) before writing code:
   goal, approach, files that will change, risks/unknowns, how it'll be
   tested. Trivial fixes (typo, one-line config change) can skip this.
2. **Critique the plan.** The critic reads `PLAN.md` against the actual
   codebase and flags anything that's wrong, missing, or riskier than it
   looks — before code exists, while it's cheap to change direction.
3. **Build.** The builder implements, updates `PLAN.md` if reality diverged
   from the plan, and writes/runs tests.
4. **Critique the diff.** The critic reviews the actual change against
   `PLAN.md` and the codebase, using the checklist in `CRITIC.md`.
5. **Respond.** The builder addresses every finding explicitly — fixed, or
   won't-fix with a one-line reason — not just the ones that seemed
   important. Loop back to step 4 until the critic has no blocking
   findings left.
6. **You merge.** Neither role approves or merges its own work. A human
   does the final read.

## Cross-repo contracts (FE ↔ BE)

Because this workflow spans two repos, the most common real bug is a
silent mismatch between what one repo assumes and what the other actually
does (a renamed field, a changed status code, an endpoint that moved). Both
`BUILDER.md` and `CRITIC.md` call this out specifically — when a task
touches the FE/BE boundary, the plan should state the contract explicitly
(request/response shape, error cases) rather than leave it implicit, and
the critic should check that contract against the *other* repo's actual
code when it's reachable, not just assume it.
