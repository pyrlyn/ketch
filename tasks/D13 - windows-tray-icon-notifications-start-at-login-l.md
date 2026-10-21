---
id: D13
title: "Windows: tray icon, notifications, start at login, links"
status: open
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

The Windows counterparts of the macOS menu-bar extra, notifications, login item and URL scheme. WinUI has no tray control, so the icon is Win32's notification area. Research: sections 1 and 3c.

Done when a notification-area icon opens the tray panel, `AppNotificationManager` posts update notices unpackaged, start at login and the `ketch` protocol are registered through `ActivationRegistrationManager` (with D8's validation rules), one instance runs at a time via `AppInstance`, and each can be switched off in Settings.
