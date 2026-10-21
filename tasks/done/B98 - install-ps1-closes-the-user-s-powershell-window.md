---
id: B98
title: "`install.ps1` closes the user's PowerShell window on error"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Under `irm … | iex` the installer runs in the caller's session. The body is a scriptblock invoked with `@args`, so its variables, functions and `$ErrorActionPreference = 'Stop'` stay inside it. Error paths throw; the catch prints the message. `exit` runs only when `$PSCommandPath` is set, which is a file launch (`-File` or `.\install.ps1`). Piped into `iex` the status is `$global:LASTEXITCODE` and the session keeps running. The caller's `KETCH_ROOT` is restored in `finally`.

Tests: `the_script_exits_only_when_run_as_a_file`, `a_failure_under_iex_leaves_the_callers_session_running`.
Each of the four switches can be turned off in Settings. They persist in `%LOCALAPPDATA%\ketch\settings.json`, so an unpackaged app and an MSIX share one file. A broken file loads the defaults rather than stopping the app.

Tests: `UpdateNoticesTests`, `AppSettingsTests`, `LinkAndNoticeTests` (`dotnet test` on `Ketch.AppCore.Tests`). The WinUI project compiles in the `ketch-win-app` job; this machine is Linux, so the icon, the toast and the registrations were not run here.
