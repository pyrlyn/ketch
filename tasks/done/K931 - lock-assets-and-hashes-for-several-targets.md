---
id: K931
title: Lock assets and hashes for several targets
status: done
created_at: 1792603887
updated_at: 1792970381
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
blocked_by:
- K910
---

`docs/LOCKFILE.md` guarantees a hash only on the target that wrote the lock, so a lock written on a Mac gives Linux CI nothing pinned. mise records a URL and checksum per platform.

Done when `ketch lock --target …` (or a `lock_targets` config key) writes per-target `asset` and `sha256`, `ketch sync` enforces them on each target, and `docs/LOCKFILE.md`, the schema and ru/uk are updated.
