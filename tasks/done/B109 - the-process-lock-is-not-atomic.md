---
id: B109
title: The process lock is not atomic
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. Done: two processes can never both hold the lock, and one's `Drop` never deletes the other's. Plan: rework `Lock::acquire_path` in `state.rs` (temp file plus no-replace link, or an OS advisory lock).
Model: Claude Code / opus-5.5 · Status: done, merged in #309 · Priority: P2 · Complexity: 4
Check: a concurrent acquire test and an empty-lock-is-busy test.
