---
id: B93
title: Installers and self upgrade never see prerelease CLI releases
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. The creator chose to keep publishing CLI releases as prereleases and to teach the readers an opt-in flag. Done: `install.sh --pre`, `install.ps1 --pre` and `ketch self upgrade --pre` (and `self install` where it picks a version) take the newest release including prereleases; without the flag they behave as today. Plan: reuse the release listing and `select_release` from `source/mod.rs` (the `--pre` path from M18–M22) in `self_update.rs` and the CLI flag in `cli.rs`; list `/releases` in both installers when the flag is given, skipping `desktop-v*` tags; docs and help in `docs/` (with `ru`, `uk`). The cask keeps `:github_latest` (noted, not changed).
Model: Claude Code / opus-5.5 · Status: done, merged in #316 · Priority: P1 · Complexity: 3
Check: a unit test of version selection with the flag, an end-to-end test of `self upgrade --pre` against the fixture source, and `tests/desktop-release.sh` still passing.
