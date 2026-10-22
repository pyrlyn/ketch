---
id: K908
title: Settle the version restart for existing installs
status: open
created_at: 1792603861
updated_at: 1792603928
priority: 1
blocked_by:
- K902
---

Versioning restarted at 0.0.1 (#359). `self_update.rs:502` refuses `to <= from`, so a ketch on 0.10.0 never upgrades again; the cask is stuck at `version "0.10.0"` because the v0.0.1 tap push failed (#367); release-failure issues #362 and #254 are still open; `/releases/latest` is an unoptimised dev build.

Done when the creator's choice (bump past 0.10, or a migration note plus a `self upgrade` path down) is recorded and implemented, the tap job is fixed and re-run, and the stale release-failure issues are closed.
