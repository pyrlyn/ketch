---
id: F7
title: Clean up target dirs with dunnage after tests
status: done
created_at: 1792583854
updated_at: 1792583854
---

`dunnage` (https://github.com/listepo/dunnage) shrinks cargo `target/` dirs losslessly (APFS compression + copy-on-write dedupe), never deletes, and keeps mtimes so nothing rebuilds. `just dunnage` runs `dunnage run target` (skipped when there is no `target/` yet), treating exit 2 (a build held the lock) as success and printing a one-line install hint instead of failing when the binary is missing. `just test` now runs it as a post-dependency (`test: && dunnage`), so a normal local test run also tidies the checkout's target dirs; `just check` picks it up the same way since it depends on `test`. CI never runs Just's `test` recipe (it calls `cargo nextest run` directly), so the guard's tolerance is for local machines without `dunnage` installed, not for keeping CI green. Installed with `ketch install dunnage` once `pyrlyn/ketch-registry#9` lands; already installed on this machine in the meantime. `toolchain.md` gained `ketch` and `dunnage` in the programs table and a new `ketch` package table. `AGENTS.md` notes the cleanup in one line under Commands.
