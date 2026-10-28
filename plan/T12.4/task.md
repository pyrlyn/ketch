---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T12.4. Settings, About, tray and update notices

The app's own preferences (`AppSettings.swift`: the update-check interval, notices, open at
login where the platform allows), Settings and About, a tray icon with the pending upgrade
count and the menu-bar panel's actions (`MenuBarContent.swift`), the periodic check, and a
notice when new upgrades appear (`UpdateNotices.swift`). Sparkle self-updates are not ported.
