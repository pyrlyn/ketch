---
id: B95
title: Windows user PATH loses `%VAR%` entries and non-ASCII text
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. Done: `shell.rs` and `install.ps1` read and write `HKCU\Environment\Path` unexpanded, keep the value kind, and decode UTF-8. Plan: switch `read_user_path`/`write_user_path` to `Microsoft.Win32.Registry` with `DoNotExpandEnvironmentNames` through the UTF-8 `powershell()` helper; same in `install.ps1`.
Model: Claude Code / opus-5.5 · Status: done, merged in #308 · Priority: P1 · Complexity: 4
Check: a Windows-gated test round-tripping a `%USERPROFILE%` entry and a non-ASCII one; Windows CI.
