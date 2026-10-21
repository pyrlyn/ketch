---
id: B78
title: Self-uninstall drops the install lock too early
status: done
created_at: 1792583854
updated_at: 1792583854
---

`uninstall_self` held `state::Lock` only for the package uninstall and `state.save`, then dropped it before the Windows registry entries, the shell startup blocks and `remove_root`. The lock file lives in the root, so another ketch could acquire it and write a tree this run was still taking apart. The lock now stays held through those edits. Shell blocks are edited before the root, because removing the root unlinks the lock file; the paths are still reported after the cask, which is the order the command already printed. The cask step stays outside the lock: `brew uninstall` runs a nested `ketch self uninstall`, and with `--keep-packages` that process still has a binary and must be able to take the lock itself.

Test: `self_update::tests::uninstall_holds_the_lock_while_the_shell_block_and_the_root_go` (Unix). A shell file opened as a fifo blocks in `read`; while it is blocked the lock is `Busy` and the lock file is still on disk. After the run the root is gone and the block is taken out of the file.
