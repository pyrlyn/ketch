---
id: B114
title: Link targets are checked lexically
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: every link is canonicalized after extraction and must stay under the destination. Plan: canonicalize in `finish_deferred_links` and `payload_executable_entry`.
Model: Claude Code / opus-5.5 · Status: done, merged in #304 · Priority: P3 · Complexity: 4
Check: a test with `L -> .` and `N -> L/L/../../..` that is refused.
