---
id: B1
title: Plugin child hang
status: done
created_at: 1792583854
updated_at: 1792583854
---

A plugin that orphans a child holding its stdout hangs ketch forever. `source/plugin.rs` kills only the direct child and then joins its reader threads inside `thread::scope`; EOF on the inherited pipe never comes while a grandchild holds it, so the deadline its own documentation promises bounds nothing. Discovery probes every plugin on every source-loading command, so one such plugin hangs `install`, `search` and `info` too. Fix: run the child in its own process group and `killpg` it on the deadline, and bound the reader side so `output()` returns within the deadline whatever the grandchildren do.

Plugin subprocesses now start in their own process group (Unix `process_group(0)`, Windows `CREATE_NEW_PROCESS_GROUP`). On the deadline, and after the child exits, the whole tree is stopped (`kill -s KILL -- -<pid>` / `taskkill /T /F`). Reader threads are joined with the remaining deadline, so `output()` returns even if a grandchild still held a pipe. Regression: `a_plugin_that_orphans_a_child_holding_stdout_is_stopped_within_the_deadline`.
