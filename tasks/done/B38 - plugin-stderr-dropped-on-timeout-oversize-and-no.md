---
id: B38
title: Plugin stderr dropped on timeout, oversize and non-UTF-8
status: done
created_at: 1792583218
updated_at: 1792583218
---

Only the non-zero-exit path carried stderr into the error; a plugin killed at the deadline, one that wrote more than 8 MiB, and one that wrote non-UTF-8 all reported without it.

Added `stderr` to `Error::Plugin` and surfaced it through `Error::details()` like `Error::Command`. `run_plugin` now passes captured stderr into `plugin_fail` on timeout, oversize stdout, and non-UTF-8 output. Tests: `stderr_is_included_when_a_plugin_times_out`, `plugin_fail_includes_stderr_in_details`.
