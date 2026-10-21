---
id: B2
title: "`install local:<fifo>` hang"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`source/local.rs` classifies by opening the path, so a FIFO with no writer blocks in `open(2)` before anything can time out, and a character device never reaches EOF. A user manifest or `--path` can still pass `local:`.

`classify` and `download` now inspect paths with `symlink_metadata` and refuse anything that is not a regular file, a symlink, or a directory before `read_head` or `fs::copy` can open them (`ensure_is_regular_file`, `ensure_local_payload`). Unit test `refuses_a_named_pipe_before_open_can_block` verifies a FIFO is rejected without hanging.
