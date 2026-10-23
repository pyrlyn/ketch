---
id: K930
title: Move OS-neutral placement code into one module
status: open
created_at: 1792603884
updated_at: 1792603884
---

`platform/unix.rs` and `platform/windows.rs` duplicate bin resolution, executable discovery, `cli_targets`, `writable`, `unplace` and extra linking (71 shared six-line windows), and `macos.rs` repeats part of `unix.rs`. Each copy compiles and is tested only on its own OS, so a fix to one silently misses the other. Extends the `move_into_store` idea in `ideas.md`.

Done when a `platform/common.rs` compiled on every OS holds the shared code with small hooks for name matching and symlink-versus-copy, both variants are unit-tested on every host, and the duplicate-window count is under 10. Split into the two subtasks below.
