---
id: M0
title: Cross-platform contracts
status: done
created_at: 1792583854
updated_at: 1792583854
---

macOS assumptions made explicit before a second backend: inventory of Unix-only APIs; `src/platform/scoring.rs`; `src/platform/unix.rs`; `extract/macos` gated; CI `check-linux`; `self_update::replace_binary` and `local::copy_tree` use unix helpers behind `cfg(unix)`. `score_macos_asset` in scoring; table-driven `is_ours` / `destination_available` / `clear_destination`; non-macOS tests for `host()`, `doctor`, `install`, and `list`.
