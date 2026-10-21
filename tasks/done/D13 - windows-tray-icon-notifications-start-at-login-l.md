---
id: D13
title: "Windows: tray icon, notifications, start at login, links"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The Windows counterparts of the macOS menu-bar extra. WinUI has no tray control, so the icon is `Shell_NotifyIcon` on a message-only window, and the panel is a borderless Acrylic window that lists pending upgrades, the running operation, Update all (with the same confirmation as the Updates page), Open, Check, Settings and Quit. Closing the main window hides it while the icon is on; Quit removes the icon and leaves.

Update notices use `AppNotificationManager`, which works unpackaged: `Register` runs before the process reads its activation, and a click opens Updates. Which upgrades count is the macOS rule (held packages never count, a package is new by `name@version`, control characters are stripped) in `UpdateNotices`, posted once from `KetchStore` after a refresh. Notices stay off until Settings turns them on.

Start at login and the `ketch` protocol go through `ActivationRegistrationManager` (`RegisterForStartupActivation`, `RegisterForProtocolActivation`), with an empty executable path so the current process is what Windows starts. A second process hands its activation to the first via `AppInstance` and exits. A `ketch://` link only opens a package page: the fake core tells a package page from any other action, the same split as the macOS fake, and the real grammar stays in `ketch_core::link` for the D12 adapter (`package_for_link`).
