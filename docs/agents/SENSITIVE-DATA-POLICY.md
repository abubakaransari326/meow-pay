# Sensitive data policy

Both roles (builder and critic), on either tool, must never read, print,
quote, or otherwise surface the contents of:

- `.env`, `.env.*`, and any `secrets/` or `credentials/` directory
- private keys and certs: `*.pem`, `*.key`, `*.p12`, `id_rsa*`
- cloud/local credential stores: `~/.aws/**`, `~/.ssh/**`, `~/.gcloud/**`,
  `~/.kube/**`, browser cookie/credential stores
- production data exports/dumps, customer PII, anything under a
  `*dump*`/`*export*`/`*backup*` path that looks like real user data

Add to this list anything specific to your org (a particular internal
tool's config, a data-warehouse export folder, etc.) — this is a starting
point, not exhaustive.

## How this is enforced, and how much to trust it

`.claude/settings.json` (both the copy in this repo and the one at
`~/.claude/settings.json` on your machine) lists `permissions.deny` rules
for these paths, and `.cursorignore` lists the same paths for Cursor. Both
mechanisms block the tool's *normal file/read/edit tools* from touching a
matching path — real, and worth having.

What they don't do: stop a shell command run with broad `Bash` permissions
from finding a workaround, or stop a tool from seeing a secret that's
sitting in an environment variable the process already inherited. So the
actual control, in order of how much you should rely on it:

1. **Don't put live secrets where an agent's shell can reach them at all.**
   Real `.env` files, real cloud credentials — keep them out of the repo
   working tree an agent runs in, and out of the shell environment you
   launch these tools from. Commit only `.env.example` with placeholders.
2. **The deny rules / `.cursorignore` above**, as a second layer, for the
   case where a sensitive file does exist on disk (a teammate's local
   `.env`, a cert someone downloaded into the repo by accident).
3. **Review**, same as with any commit: if a diff touches something on the
   sensitive list, that's a blocking finding for the critic regardless of
   what the deny rules did or didn't catch.

Re-verify (1) and (2) actually work in your installed versions before
relying on them — e.g. deliberately ask a session to read a dummy `.env`
you create for the test and confirm it refuses.
