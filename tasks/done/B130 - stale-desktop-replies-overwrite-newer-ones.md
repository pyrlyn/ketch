---
id: B130
title: Stale desktop replies overwrite newer ones
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. Done: out-of-date search and changelog replies are dropped, and Windows search is debounced. Plan: request tokens in the macOS views and the Windows store/page.
Model: Claude Code / opus-5.5 · Status: done, merged in #314 · Priority: P3 · Complexity: 3
Check: store tests in Swift and in `Ketch.AppCore.Tests`.
