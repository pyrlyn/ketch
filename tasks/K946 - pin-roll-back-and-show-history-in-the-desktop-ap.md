---
id: K946
title: Pin, roll back and show history in the desktop apps
status: open
created_at: 1792603911
updated_at: 1792603929
blocked_by:
- K923
---

All three apps show held packages but cannot pin or unpin, roll back or show history, although `pin`, `unpin`, `rollback`, `history` and `info` are exported. The core work is done; only protocol and UI are missing. One subtask per app.

Done when every app's package page has Pin/Unpin, "Roll back to X" when a retained version exists, and a History tab fed by `history(package, 50)`, with store tests on the new scenarios.
