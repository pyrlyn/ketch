---
id: B104
title: A failed placement loses the old links and app copy
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: occupied destinations are moved aside and restored on failure on every platform. Plan: an aside-and-restore step in `platform/unix.rs`, `macos.rs`, `windows.rs` placement, building on B79.
Model: Claude Code / opus-5.5 · Status: done, merged in #296 · Priority: P2 · Complexity: 5
Check: a test that fails the second link and finds the old link back.
