---
id: D7
title: "macOS: update notifications"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The macOS app checks for updates on a timer (F12) but tells nobody unless the window or menu-bar panel is open. Builds on F12's live core; F12's remaining work (the `LiveKetchCore` adapter and the manual checks) stays in F12. Research: section 1.

Done when new upgrades since the last notice post one `UNUserNotificationCenter` notification, authorisation is asked only when the user turns notifications on in Settings, clicking it opens Updates, and a unit test covers which upgrades count as new.

Execution plan:

1. `Ketch/Store/UpdateNotices.swift`: the pure rule (`fresh(updates:notified:)`: held packages never count, an upgrade is new by `name@version`, a version already noticed is not new again), the notice text (at most three names, control characters stripped, since versions come from release tags), an `UpdateNotifier` protocol, and `SystemUpdateNotifier` over `UNUserNotificationCenter` plus its delegate.
2. `AppSettings`: `notifiesOfUpdates` (off by default) and the set of already-noticed upgrades, in UserDefaults.
3. `KetchStore`: after each background check, post one notice for the new upgrades and remember them; `setNotifications(_:)` asks authorisation only when the user turns the switch on, and turns it back off with an explanation when macOS refuses. A click sets `requestedSection = .updates` and asks for the main window; `ContentView` switches to Updates, `MenuBarLabel` (always alive) opens the window if it was closed.
4. Settings: a "Notify when updates are available" toggle in General.
5. Tests: `UpdateNoticesTests` for which upgrades count as new, store tests against a fake notifier (one notice for several new upgrades, none for held or already-noticed ones, none when off, authorisation asked only on turning on, refusal turns it off).
6. Verify: `swift-format lint --strict`, `just macos-test` unit tests (the UI test needs an unlocked screen; CI runs it).
