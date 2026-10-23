---
id: K903
title: Make every CI job gate merges to main
status: open
created_at: 1792603857
updated_at: 1792603928
priority: 0
blocked_by:
- K902
---

Ruleset `protect-main` lets the admin role bypass checks, and its 15 required checks leave out `ketch-cs`, `ketch-win-app`, `ketch-capi`, `ketch-linux-app` and `hakari`. 30 of the last 40 merged pull requests had non-green checks; five merged with none green. Superseded `main` runs are cancelled, and a cancelled run never fires `release-ci-alert`, so a red `main` went unnoticed for three days.

Done when one aggregate `ci / gate` job (`needs:` every job, `if: always()`) is the required check, the bypass is off or merges go through a merge queue, and an alert fires when `main` has had no green run for 24 hours, cancelled runs included.
