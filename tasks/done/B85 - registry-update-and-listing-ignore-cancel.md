---
id: B85
title: Registry update and listing ignore cancel
status: done
created_at: 1792583854
updated_at: 1792583854
---

Each of the four switches can be turned off in Settings. They persist in `%LOCALAPPDATA%\ketch\settings.json`, so an unpackaged app and an MSIX share one file. A broken file loads the defaults rather than stopping the app.

Tests: `UpdateNoticesTests`, `AppSettingsTests`, `LinkAndNoticeTests` (`dotnet test` on `Ketch.AppCore.Tests`). The WinUI project compiles in the `ketch-win-app` job; this machine is Linux, so the icon, the toast and the registrations were not run here.
