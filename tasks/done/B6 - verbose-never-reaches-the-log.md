---
id: B6
title: "`--verbose` never reaches the log"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`--verbose` detail never reached the log, which `README.md` and the module comment in `log.rs` promise it does. `ui::debug` records at `Level::Debug`, but the sink wrote only records at or below `cfg.log_level`, and `--verbose` raised only the terminal level.

`log::init` now takes the verbose flag and `file_level` raises the sink to `Debug` when `--verbose` is set and logging is not off. Unit test `verbose_writes_debug_detail_to_the_log_file` verifies debug detail is written at the default info level when verbose is on.
