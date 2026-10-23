---
id: K924
title: Set up PATH from the macOS app on first launch
status: open
created_at: 1792603876
updated_at: 1792603929
priority: 1
blocked_by:
- K922
- K923
---

No app calls `path_status` or `path_install`, so tools installed from the GUI do not run in a terminal until someone runs `ketch path install`, and someone who installed only the `.dmg` has no CLI to run it with. A Finder launch never sees a shell-profile `KETCH_ROOT`, so app and CLI can silently use different roots.

Done when a first-run sheet shows the root, the bin dir and the per-shell status, previews `path_install(dry_run: true)` and applies it on consent, Settings can reopen it, and store tests run on the new scenarios. Windows and Linux follow as their own tasks once this shape is settled.
