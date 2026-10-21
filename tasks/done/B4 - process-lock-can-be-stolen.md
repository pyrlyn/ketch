---
id: B4
title: Process lock can be stolen
status: done
created_at: 1792583854
updated_at: 1792583854
---

The process lock can be stolen from a live holder. B reads a dead pid, forks `ps` to check, and by the time it renames, A has reclaimed the lock — `rename` is not compare-and-swap. A failed pid write is also swallowed, leaving an empty lock everyone treats as stale. Fix: after the rename, re-read the moved file and claim it only if it still holds the value judged stale; otherwise rename it back and report `Locked`.

After `rename`, `take` re-reads the moved file and keeps the claim only if it still holds that stale value; otherwise it renames the file back and returns `Locked`. A failed pid write removes the empty lock and surfaces the IO error. Tests: `take_does_not_steal_a_lock_that_changed_during_the_stale_check`, `a_failed_pid_write_does_not_leave_an_empty_lock`.
