---
id: F4
title: Self upgrade, in-use processes, auto-update
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ketch self update` is now `ketch self upgrade` (same verb as client packages). `self update` remains a clap alias so the Homebrew cask and existing scripts keep working. `ketch upgrade` and `ketch self upgrade` list other processes running from a file about to be replaced, ask whether to stop them, and on yes TERM then KILL (taskkill /F on Windows); a decline leaves them running and replacement continues as before. The current ketch pid is never offered. `auto_update` in `config.toml` / `KETCH_AUTO_UPDATE` defaults to `true`: `install` and `upgrade` refresh the registry and print that auto-update is enabled; a failed fetch is a warning. `false` leaves previous behaviour. Offline e2e sets `KETCH_AUTO_UPDATE=false`.

Tests: occupant listing and `--yes` stop in `src/process.rs`; CLI e2e in `tests/auto_update.rs` and `tests/self_update.rs` (alias + dry-run verbs).
