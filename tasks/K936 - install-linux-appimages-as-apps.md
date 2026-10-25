---
id: K936
title: Install Linux AppImages as apps
status: in-progress
created_at: 1792603895
updated_at: 1792952896
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
---

"Apps as well as binaries" holds only for macOS `.app`; `platform/mod.rs:493` rejects `.appimage`, the format most Linux GUI apps ship.

Done when an AppImage declared as an app is placed with a `.desktop` entry and icon in the user XDG directories, recorded in state, removed on uninstall, and covered by a Linux end-to-end test.
