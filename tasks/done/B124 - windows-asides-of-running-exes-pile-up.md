---
id: B124
title: Windows asides of running exes pile up
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. Done: `*.old.*` siblings of recorded links are swept at commit, relink and uninstall. Plan: a sweep in `platform/windows.rs`.
Model: Claude Code / opus-5.5 · Status: done, merged in #299 · Priority: P3 · Complexity: 3
Check: a Windows-gated test.
