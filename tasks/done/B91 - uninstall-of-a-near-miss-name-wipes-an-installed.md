---
id: B91
title: Uninstall of a near-miss name wipes an installed store
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: a leftover dir is removed only when its on-disk name is byte-equal to the request and no recorded prefix lies under it, with confirmation and `--dry-run` honoured, in the CLI and `ketch-ffi`. Plan: tighten `package_dir_candidate`/`remove_package_dir` in `install.rs`; route the confirmation through `cmd/pkg.rs`.
Model: Claude Code / opus-5.5 · Status: done, merged in #317 · Priority: P1 · Complexity: 3
Check: a test where `uninstall RipGrep` on a case-insensitive store refuses and leaves `store/ripgrep`.
