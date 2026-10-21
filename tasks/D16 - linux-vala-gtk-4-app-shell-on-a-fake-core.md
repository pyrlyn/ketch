---
id: D16
title: "Linux: Vala + GTK 4 app shell on a fake core"
status: open
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

The Linux app's screens in Vala with GTK 4 and libadwaita, following the GNOME HIG, built before the C ABI is ready. GTK 4 + libadwaita over KDE and Blueprint for the markup were decided by the creator (2026-10-01, open decisions 1 and 2). Research: sections 3b, 3c and 5.

Done when a Meson project builds a libadwaita app with the common screens in an `AdwNavigationSplitView` that adapts to narrow windows, Blueprint files (pinned as a Meson subproject) for the UI, `AdwAlertDialog`, `AdwBanner` and toasts for decisions, busy and finished work, dark and high-contrast styles, all on a fake core reading D4's fixtures, with a Linux CI job that builds and runs its tests.
