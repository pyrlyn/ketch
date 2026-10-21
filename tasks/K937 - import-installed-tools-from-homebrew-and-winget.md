---
id: K937
title: Import installed tools from Homebrew and winget
status: open
created_at: 1792603896
updated_at: 1792603896
---

`ketch import` takes one name at a time, but switching package managers is a bulk decision; "23 of 40 tools can move" converts users.

Done when `ketch import brew --installed` and `ketch import winget --file export.json` convert what they can, print a table of what was skipped and why, honour `--dry-run`, and fixture tests cover both.
