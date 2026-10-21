---
id: B101
title: Read-only directory modes break extraction and removal
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: directory modes are applied after all members, always with `u+rwx`. Plan: defer directory modes in `extract/archive.rs`.
Model: Claude Code / opus-5.5 · Status: done, merged in #302 · Priority: P2 · Complexity: 3
Check: a test with a `0o555` directory before and after its children that extracts and is removable.
