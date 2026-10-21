---
id: A3
title: Multi-version side-by-side and aqua parity — evaluated, not built
status: done
created_at: 1792583854
updated_at: 1792583854
---

Audit item 13, verdict after reading the tree; no code changed. Side-by-side versions are already answered by retention: upgrades keep the previous prefix (`retained` in state, default `keep = 1`), `ketch rollback` relinks without redownloading, `ketch prune` drops old ones (see M6 in this file). Two versions on PATH at once is deliberately not a thing: one bin dir, one link per name. Global lockfile UX is already shipped (`ketch lock` / `--check` / `sync`, see `docs/LOCKFILE.md`); built-in catalog is already shipped (`src/builtin.toml` tiers in `src/manifest.rs`). No gap to fill, no issues filed.
