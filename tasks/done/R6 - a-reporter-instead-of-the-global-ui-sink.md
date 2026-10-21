---
id: R6
title: A reporter instead of the global `ui::` sink
status: done
created_at: 1792583854
updated_at: 1792583854
---

The core reports typed events through `Report` and `Ctx`. It does not call `ui::` (the only match under `crates/ketch-core/src` is a comment in `config.rs`). The CLI's `ui.rs` is the renderer: colour, verbosity and emoji live there, and `log::record` is reached from that renderer. A non-CLI host uses `LogReporter`. The TUI is the same renderer's full-screen mode; `renderer_projects_state_without_a_real_terminal` draws it on `TestBackend`. `--tui` with no terminal falls back to the line renderer.

Test: `a_local_install_reports_each_stage_through_the_reporter` records each install stage and asserts nothing was printed.
`stats` is a cargo feature of `ketch-core`: diesel, diesel_migrations and libsqlite3-sys are optional, and `stats::record` returns without writing when the feature is off. `scripts/core-features.sh` builds the core with no optional feature and then with each one alone, plus `ketch-ffi` on its own set. `just core-features` and the CI step run that script. Landed in #319.
