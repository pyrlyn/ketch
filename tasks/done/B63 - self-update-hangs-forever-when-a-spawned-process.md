---
id: B63
title: "`self update` hangs forever when a spawned process never starts"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`process::run_bounded` runs the child off the caller's wait, because an antivirus filter can hold process creation itself. After 30s a process listing is stopped and warned about by program and pid. After 60s `ketch --version` is stopped, the error says so, and restore puts the previous binary back. A spawn that never returns has no pid; the message says the wait was ended, and the spawn thread stops the child if creation later succeeds.

Tests: the stopped-probe wording, a command that outlives a short budget and is no longer alive, and on Windows `tree.com` (exit 0) / `where.exe` (exit 1). Verified on macOS with the same clippy and nextest run as B61. The `cfg(windows)` cases run in CI.
