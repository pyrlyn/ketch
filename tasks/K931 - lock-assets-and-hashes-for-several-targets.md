---
id: K931
title: Lock assets and hashes for several targets
status: open
created_at: 1792603887
updated_at: 1792603929
blocked_by:
- K910
---

`docs/LOCKFILE.md` guarantees a hash only on the target that wrote the lock, so a lock written on a Mac gives Linux CI nothing pinned. mise records a URL and checksum per platform.

Done when `ketch lock --target …` (or a `lock_targets` config key) writes per-target `asset` and `sha256`, `ketch sync` enforces them on each target, and `docs/LOCKFILE.md`, the schema and ru/uk are updated.
