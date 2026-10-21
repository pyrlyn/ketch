---
id: B77
title: A busy `ketch.exe` aborts `self uninstall`
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B77 in the 2026-10-08 findings. `self uninstall` removed the running executable with `?`, so a busy Windows `.exe` stopped the rest of the uninstall.

Status: done (Claude Code / opus-5.5). The fix was already on `main` in 5152c87 (the step warns and continues) but had no test. Test: `self_update::tests::uninstall_warns_and_finishes_when_the_running_binary_cannot_be_removed`, on every OS, which fails with the old `?` restored.
