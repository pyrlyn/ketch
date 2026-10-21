---
id: D3
title: "`ketch-ffi`: the remaining CLI operations"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Screens the apps already draw (Activity history, package info, pin, rollback, Doctor fixes, PATH status) have no core call behind them. Research: section 3a, gap G6.

Done when history (`stats.db`), info, pin/unpin, rollback, prune, registry refresh, `path` status and install, a doctor fix action and reading ketch's config are exported as thin calls into existing core code, each with a test; `registry push` stays out (it owns a tokio runtime).

Execution plan (Claude Code / opus-5.5):

1. Core first, so the CLI and the binding share one path: `shell::status`, `shell::detected` and `shell::install_here` (the PATH report and setup the CLI computed inline), `doctor::fix` (moved from `cmd/system.rs`), `install::pin` (from `cmd/pkg.rs`), `Resolver::resolve_or_recorded` (the registry, else the manifest recorded at install time), and a new `info` module whose `gather` is what `ketch info` assembled. The CLI commands call these and only format.
2. `crates/ketch-ffi`: `history`, `info`, `pin`/`unpin`, `rollback`, `prune`, `registry_refresh`, `path_status`, `path_install`, `doctor_fix` and `config`, each a thin call into step 1 or existing core code, with records `HistoryEvent`, `PackageInfo`, `Pruned`, `PathStatus`, `ShellSetup`, `PathChange` and `Settings`. Mutating calls hold the lock, as the CLI does.
3. A Rust unit test per call in `lib.rs`; `doctor_fix` runs against a scratch `HOME` on Unix only.
4. Swift binding test: pin holds an upgrade, unpin lets it through, rollback undoes it, history records all three, info carries the record, and path status plus settings describe the scratch root.
5. Verify: `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo nextest run --workspace`, `just ffi-test`.

Status: done. `Settings` reports only whether a GitHub token is set, never the token. `prune` with a `keep` stores it as the retention setting, as `ketch prune --keep` does. An empty name list means every installed package for `pin`, `unpin` and `prune`, as on the command line. `registry push` stays out: it owns a tokio runtime. `changelog::manifest_for` from D2 became `Resolver::resolve_or_recorded`, shared with `info`.
