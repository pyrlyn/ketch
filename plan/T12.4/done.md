# T12.4. Done

`desktop/src/main/kotlin/ketch/desktop/`: the app's own preferences, Settings and About, the
tray, open at login and update notices.

- `settings/AppSettings.kt`: `Prefs` (check interval of 15, 60, 360 or 1440 minutes,
  prereleases, notices, the upgrades already announced) as a `StateFlow` on `KeyValues`:
  `SystemKeyValues` (`java.util.prefs`, node `io/github/pyrlyn/ketch`, the macOS app's
  UserDefaults key names) in the app, `MemoryKeyValues` in tests. An unreadable value falls
  back to its default and an interval not offered is refused. Nothing of ketch's config is
  copied and no token is stored.
- `store/UpdateNotices.kt`: the pure rule (`key` is name@latest, `fresh` leaves out held and
  announced upgrades, `message` lists three and counts the rest, `printable` drops control and
  format characters) and `UpdateChecks`: turning notices on asks the notifier once and a
  refusal leaves them off with a message; a check posts one notice and replaces the announced
  set; `run` checks now and then every interval, starts over when the interval changes and
  skips a check while an operation runs. The app's notifier posts through the tray icon; a
  system without a tray refuses.
- `settings/LoginItem.kt`: for the packaged app only (`jpackage.app-path`): a LaunchAgent
  plist on macOS, an XDG autostart entry on Linux (Exec quoted and escaped per the Desktop
  Entry spec), the `HKCU\…\Run` value through `reg` on Windows. A development run shows the
  switch disabled.
- `ui/screens/Settings.kt`: the Settings section (interval, prereleases, notices, open at
  login, the ketch root and Open config.toml from the core's `config`, the token note) with
  About (`ui/About.kt`: app and core version, licences, repository link). About and Settings
  are also on the macOS app menu. Installs from Discover and the package page take the
  prerelease setting.
- `Navigation` holds the section, the Update all confirmation, About and the window's
  visibility, so the tray and the app menu steer the window.
- `ui/Tray.kt` (planned as `TrayMenu.kt`): the menu as a pure model (summary, up to five
  updates, Update all, the running operation with Cancel, Open, Check for updates, Settings,
  Quit), the tooltip, and the icon drawn with the pending count. Closing the window hides it
  while there is a tray; Quit ends the app. Update all only opens the window's confirmation.
- `KetchStore.downloading` moved to the file's top level to keep the class within detekt's
  function limit once `config` was added.

Not ported: Settings → Appearance (tint, glass, accent, wash), the macOS Liquid Glass look;
Sparkle. Clicking a notice is not routed anywhere: Compose's tray offers only the icon's
`onAction`, which opens the window. The Windows `reg` quoting is unit-tested against a fake
`reg` only, not on Windows. `java.util.prefs` on macOS writes to the real user's preferences
whatever `HOME` says; only the app writes there, tests use memory.

Commits: `74651f1 T12.4: claim and plan`, `cd8b3fe T12.4: app preferences, update notices and
the periodic check`, `fcc7fbe T12.4: open at login for the packaged app`, `9add913 T12.4:
Settings, About, tray and the system menus`.

Checked: `./gradlew build` green (ktlint, detekt, all tests); 106 desktop tests, 41 new
(`UpdateNoticesTest` 7, `UpdateChecksTest` 9 with the periodic check on virtual time,
`AppSettingsTest` 4, `LoginItemTest` 7 in a temporary home with a fake `reg`, `TrayTest` 6,
`SettingsTest` 8 Compose tests for Settings, About and the tray's routes into the window).
`./gradlew :desktop:run` with a scratch `HOME` and `KETCH_HOME` started and spawned
`ketch serve`, with no errors in the log. The window and the tray icon were not looked at:
screen access to the app was declined.
