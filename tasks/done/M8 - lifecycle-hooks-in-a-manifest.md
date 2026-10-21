---
id: M8
title: Lifecycle hooks in a manifest
status: done
created_at: 1792583218
updated_at: 1792583218
---

A manifest's `[hooks]` table names one shell line for each of `before_install`, `after_install`, `before_update`, `after_update`, `before_uninstall` and `after_uninstall`. `src/hooks.rs` runs them with `sh -c` (`cmd /C` on Windows), `KETCH_HOOK`, `KETCH_PACKAGE`, `KETCH_VERSION`, `KETCH_PREVIOUS_VERSION`, `KETCH_PREFIX`, `KETCH_BIN_DIR` and `KETCH_ROOT` in the environment, and the store prefix as the working directory whenever it exists. `install::commit` runs the before hook ahead of placement and the after hook once `state` records the package; `install::uninstall` does the same around unplace and removal. A reinstall of the same version is an install, not an update. A failing `before_*` hook stops the operation with its stderr as the detail; a failing `after_*` hook is a warning. Rollback runs the two update hooks; prune runs none. A hook is killed with its process tree after ten minutes via `plugin::run_with_deadline`, the runner source plugins already used.

Hooks are a trust boundary: they run only from a user-tier manifest (`~/.ketch/manifests`). A registry or built-in manifest carrying hooks is refused at install before anything is placed, and skipped with a warning on uninstall. `Manifest::validate` refuses a blank hook. Schema and semantics are in `docs/MANIFESTS.md`.

Tests: `src/hooks.rs` (environment, working directory, stderr detail, origin gate), `src/model.rs` (parsing, blank hook, misspelt key), `tests/install.rs` (order across install, upgrade and uninstall; a failing before hook installs nothing).
