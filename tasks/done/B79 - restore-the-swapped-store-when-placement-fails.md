---
id: B79
title: Restore the swapped store when placement fails
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-08 cloud review: a failure after the store swap could leave state naming a tree that was not on disk. On re-check it was already fixed by 5152c87: unix, macOS and Windows `place` keep `<ver>.old` and restore it on error, so `ScopedDir` being off for the same prefix is safe.
Model: Claude Code / opus-5.5 · Status: done 2026-10-12 (already fixed in 5152c87) · Priority: P1 · Complexity: 4
Check: `a_failed_place_after_the_swap_restores_the_previous_store` in `platform/unix.rs` and `platform/macos.rs`.
Also through `commit`: `install::tests::a_reinstall_whose_linking_fails_after_the_swap_puts_the_previous_store_back` (unix), an in-place reinstall whose linking fails after the swap; it fails with the restore call removed. B104 stays a separate task.
