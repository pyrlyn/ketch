---
id: K953
title: Split the oversized modules along their seams
status: open
created_at: 1792603919
updated_at: 1792603919
priority: 3
---

Review and merge conflicts concentrate in a few files: `install.rs` (about 2,000 lines of code; `prepare` 274 lines, `commit` 215), `src/ui.rs` (1,365), `shell.rs` (1,504, with an inline `windows_env`), `model.rs` (about 1,600, 35 public types). One subtask per file, each a pure move with no test changes.

Done when every subtask has landed.
