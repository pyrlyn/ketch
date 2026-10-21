---
id: B26
title: Foreign operating systems still scored
status: done
created_at: 1792583854
updated_at: 1792583854
---

A release shipping only `freebsd`/`netbsd`/`plan9` assets had one accepted (as an emulated `x86_64` build) and linked instead of failing.

`FOREIGN_OS_TOKENS` in `src/platform/mod.rs` lists unsupported OS name tokens (`freebsd`, `netbsd`, `openbsd`, `plan9`, `dragonfly`). `names_foreign_os` in `src/platform/scoring.rs` rejects them in every platform scorer before architecture matching, so they are never scored as emulated x86_64. Regression: `foreign_operating_systems_are_never_selected`.
