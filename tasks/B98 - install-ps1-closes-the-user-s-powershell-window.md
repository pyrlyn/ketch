---
id: B98
title: "`install.ps1` closes the user's PowerShell window on error"
status: in-progress
created_at: 1792583218
updated_at: 1792583218
---

Row B98 in the 2026-10-12 findings. Done: the body runs in a function, error paths `throw`/`return`, and `$ErrorActionPreference` is restored.

Plan: wrap `install.ps1`; keep `--help` and arguments working. Check: a Pester-free script test (or CI step) that sources the script with a failing argument and the session survives.
