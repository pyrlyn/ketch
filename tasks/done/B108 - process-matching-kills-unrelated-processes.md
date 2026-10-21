---
id: B108
title: Process matching kills unrelated processes
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. Done: occupants match whole arguments against exact canonical paths, preferring exe and open-fd evidence. Plan: rewrite `cmd_hits` in `process.rs`.
Model: Claude Code / opus-5.5 · Status: done, merged in #310 · Priority: P2 · Complexity: 3
Check: unit tests where `…/bin/rga` and an editor with the path in its arguments are not matched.
