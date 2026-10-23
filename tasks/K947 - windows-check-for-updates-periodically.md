---
id: K947
title: "Windows: check for updates periodically"
status: open
created_at: 1792603914
updated_at: 1792603914
---

macOS refreshes on a timer and has a prerelease setting; Windows refreshes only at launch and on the tray's "Check now", so a tray app started at login announces updates once. Every Windows refresh failure raises an alert (the Windows side of B129).

Done when a timer refreshes at an interval set in Settings (skipped while an operation runs), a background failure is logged, the prerelease switch reaches `InstallOptions`, and `Ketch.AppCore.Tests` covers it.
