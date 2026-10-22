---
id: K922
title: Make Doctor actionable in the desktop apps
status: open
created_at: 1792603874
updated_at: 1792603874
priority: 1
---

Doctor's PATH check returns CLI prose ("Run `ketch path install`") and reads this process's PATH (`shell.rs:302-327`, `config.rs:407-413`), so a GUI started from Finder or the Start menu always warns "not in this shell". `doctor_fix` is exported and unused.

Done when each check carries a machine-readable fix kind (none, `path_install`, `new_shell`), `doctor.json` is regenerated, and the apps offer a Fix button for `path_install` and hide "open a new shell".
