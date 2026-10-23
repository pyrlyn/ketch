---
id: K960
title: Run a tool once without linking it
status: open
created_at: 1792603928
updated_at: 1792603928
priority: 3
---

Trying a tool, or running it once in CI, should not change PATH; pkgx owns that use case. The unlinked install path already exists (`--no-link`).

Done when `ketch run owner/repo -- args` installs into the store unlinked (or reuses a copy), execs with the exit code passed through, `ketch prune` cleans such copies, and an end-to-end test covers it.
