---
id: D19
title: "Linux: packaging and release"
status: open
created_at: 1792583854
updated_at: 1792583854
priority: 3
---

How the Linux app reaches users. R10 left Flatpak against distribution packages open, and Flatpak needs home access for PATH work.

Done when the creator has chosen the format, CI builds it on `desktop-linux-v*` tags without touching `/releases/latest`, the app ships AppStream metadata and a `.desktop` file that validate, and updates come from the chosen package manager.
