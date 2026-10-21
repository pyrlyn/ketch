---
id: R6
title: A reporter instead of the global `ui::` sink
status: in-progress
created_at: 1792583218
updated_at: 1792583218
---

The pipeline prints through `ui::` directly (`install.rs`, `self_update.rs`, `registry.rs`, `listing.rs`), and `ui.rs` keeps global state for the TUI. Replace that with a `Reporter` passed into the core (or typed events on a channel, generalising `tui::Event`): progress, status, warning, log. The CLI implements it with today's `ui.rs`, the TUI with its events. `ui.rs` stays the only place that prints, and `log::record` is still reached through it.

Done when the core has no `ui::` calls, CLI output is byte-for-byte the same (the existing `trycmd`/`insta` snapshots pass unchanged), and a test reporter can assert the events of an install.

Plan:
1. Inventory (as of f60d85e): `ui::` calls outside `cmd/`, `ui.rs` and `main.rs` — `install.rs` 35, `self_update.rs` 27, `registry.rs` 8, `listing.rs` 7, `http.rs` 5, `hooks.rs` 4, `process.rs` 4, `trust.rs` 4, `log.rs` 3, `source/mod.rs` 3, and 1–2 each in `changelog`, `config`, `manifest`, `resolve`, `state`, `stats`, `extract`, `platform/unix`, `platform/windows`, `source/{github,local,plugin}`. Globals in `ui.rs`: `COLOR`, `LEVEL`, `BARS` (indicatif `MultiProgress`), `TUI`, `TUI_INPUT_PAUSE`; `log.rs` has `SINK`.
2. Define in the core `pub enum Event` (typed, not rendered strings): `Step { package, stage }` (resolve, download, verify, extract, link, hooks), `Progress { id, done, total }`, `Status`, `Warn`, `Note` (verbose), each carrying structured fields; and `pub trait Reporter: Send + Sync { fn event(&self, e: Event); }`. Core entry points take `&dyn Reporter` (via a small `Ctx { cfg, reporter, … }` so signatures do not grow per task).
3. Convert module by module, smallest first, `install.rs` and `self_update.rs` last; each step is its own commit with snapshots unchanged.
4. The binary's `ui.rs` implements `Reporter` by rendering exactly today's lines and bars; the TUI controller implements it by mapping `Event` to its own events (drop the string-stripping path in `ui::line`). `log::record` stays called from `ui.rs` only; a non-CLI host gets a `LogReporter` adapter in the core that records events to the log file, so the GUI's operations are logged too.
5. Colour and verbosity become renderer settings, not core globals; `log::SINK` is initialised by the host.
6. AGENTS.md "Conventions": "All output goes through `ui::`" becomes "The core reports through `Reporter`; only `ui.rs` prints".

Check: `grep 'ui::' crates/ketch-core/src` is empty; snapshots unchanged; a new unit test installs a fixture package with a recording reporter and asserts the event sequence; `--tui` still works (manual run against a `KETCH_ROOT` scratch tree).

Status: PR https://github.com/pyrlyn/ketch/pull/189 merged 2026-10-01. `ui.rs` and `tui/` left the core; the core reports through `Ctx { cfg, report }`, with `LogReporter` and a test `Recorder`. Remaining: colour and verbosity are still global inside `ui.rs` (plan step 5); the TUI still receives events through `ui::Terminal`; the `--tui` check ran on a zero-size pty, so it is weak and needs a manual run on a real terminal.
