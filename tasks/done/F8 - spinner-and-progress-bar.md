---
id: F8
title: Spinner and progress bar
status: done
created_at: 1792583218
updated_at: 1792583218
---

Long steps used to sit silent until the download bar. `ui::activity` now draws a progress bar when the total is known and a spinner otherwise, in rtok's form (`{spinner:.cyan} {msg}` on stderr, 120ms tick, nothing when stderr is not a terminal). Resolve, extract, registry fetch and self-update hold one for the length of the work. `ketch list` counts packages with `counter` while `latest` is looked up. Quiet mode, a pipe and the full-screen renderer draw nothing.
Model: Cursor / grok 4.7 · Status: done 2026-10-09 · Priority: P2 · Complexity: 3 · Files: `src/ui.rs`, `crates/ketch-core/src/install.rs`, `crates/ketch-core/src/listing.rs`
Check: `a_known_total_is_a_bar` and `an_unknown_total_is_a_spinner` in `src/ui.rs`.
