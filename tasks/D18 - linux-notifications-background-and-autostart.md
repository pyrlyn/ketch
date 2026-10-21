---
id: D18
title: "Linux: notifications, background and autostart"
status: open
created_at: 1792583854
updated_at: 1792583854
priority: 3
---

GNOME has no tray in its HIG; an app that checks in the background asks the Background portal and notifies through `GNotification`. Research: sections 1 and 3c.

Done when update notices go through `GNotification` (the Notification portal under Flatpak), background running and start at login are requested through the Background portal (libportal) with an XDG autostart entry outside a sandbox, `ketch://` links follow D8's rules, and there is no tray: the creator deferred a StatusNotifierItem tray (2026-10-01, open decision 8).
