---
id: R19
title: Heavy subsystems out of the always-built core
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row R19 in the 2026-10-08 findings (issue #222). Done: `stats` (diesel), `push` (octocrab, tokio), `trust` (sigstore, pgp) and the `extract` codecs are optional in `ketch-core`, so `ketch-ffi` and the desktop apps link only what they use, and the CLI keeps every feature on by default.

Split into R19.1–R19.4 below (#319–#322, stacked in that order). Plan: one sub-task per subsystem, each a cargo feature in `crates/ketch-core/Cargo.toml` with the CLI enabling it, the FFI choosing its set, and CI building the core with `--no-default-features` plus each feature alone. Check: `cargo hack`-style builds of each feature alone (or a scripted loop if `cargo-hack` is not pinned) and the full suite.
