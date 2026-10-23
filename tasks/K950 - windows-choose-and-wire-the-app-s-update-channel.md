---
id: K950
title: "Windows: choose and wire the app's update channel"
status: open
created_at: 1792603917
updated_at: 1792603917
---

D14 shipped the release workflow but left the update route open; without one every release after the first reaches nobody who already has the app. Options: an in-app check of `desktop-windows-v*` releases that notifies and opens the download page (reuses D13's notifications), Velopack (in-place, changes the app), winget (needs a Microsoft account).

Done when the creator's choice is recorded and the chosen route works end to end against a draft release, covered by `tests/desktop_windows_release.py`.
