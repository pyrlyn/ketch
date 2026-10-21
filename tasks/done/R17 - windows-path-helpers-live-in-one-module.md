---
id: R17
title: Windows PATH helpers live in one module
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row R17 in the 2026-10-08 findings. The Windows PATH, PowerShell profile and cmd AutoRun string helpers were compiled on every OS and silenced with `allow(dead_code)`.

Done: they sit in `windows_env`, compiled on Windows and in tests. A Unix build that is not a test does not include them.
