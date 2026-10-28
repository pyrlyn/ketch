---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T12.4. Settings, About, tray and update notices

The app's own preferences (`AppSettings.swift`: the update-check interval, notices, open at
login where the platform allows), Settings and About, a tray icon with the pending upgrade
count and the menu-bar panel's actions (`MenuBarContent.swift`), the periodic check, and a
notice when new upgrades appear (`UpdateNotices.swift`). Sparkle self-updates are not ported.

## Plan

1. `settings/AppSettings.kt`: the app's own preferences (check interval of 15, 60, 360 or
   1440 minutes, prereleases, notices, the upgrades already announced) as a `StateFlow`,
   behind a small key-value interface: `java.util.prefs` in the app, memory in tests. An
   unreadable value falls back to its default. Nothing of ketch's own config is copied.
2. `store/UpdateNotices.kt`: the pure rule (`key`, `fresh`, `message`, `printable`) and
   `UpdateChecks` on the store: turning notices on asks the notifier once, a check posts one
   notice for upgrades nobody announced, held ones never count, and the periodic check runs
   now and then every interval, restarting when the interval changes and skipping a check
   while an operation runs. The notifier is an interface; the app's posts through the tray.
3. `settings/LoginItem.kt`: open at login where the platform allows, for the packaged app only
   (`jpackage.app-path`): a LaunchAgent plist on macOS, an XDG autostart entry on Linux, the
   `HKCU\…\Run` value on Windows. A development run shows the switch disabled.
4. UI: a Settings section (interval, prereleases, notices, open at login, the ketch root and
   `config.toml` from the core's `config`, the token note) with About (app and core version,
   licences, repository link), also on the macOS app menu. Installs take the prerelease
   setting. Navigation moves out of `KetchApp` so the tray and a notice can steer it.
5. Tray (`TrayMenu.kt` as a pure model, drawn by Compose's `Tray`): an icon with the pending
   count, the summary, up to five updates, the running operation with Cancel, Update all
   (opening the window's confirmation), Open, Check for updates, Settings and Quit. Closing
   the window hides it while the tray lives; Quit ends the app.
6. Tests: `UpdateNoticesTests.swift` and its store suite, settings round trips, login items in
   a scratch home with a fake `reg`, the tray model, the periodic check on virtual time, and
   Compose tests for Settings and About.

Not here: Settings -> Appearance (tint, glass, accent, wash) is the macOS Liquid Glass look the
port does not imitate; Sparkle; icons and scheme registration (T12.5).

Check: `./gradlew build` green, and `./gradlew :desktop:run` shows the tray and Settings.
