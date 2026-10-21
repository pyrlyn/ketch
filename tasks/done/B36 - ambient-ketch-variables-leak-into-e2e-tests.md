---
id: B36
title: Ambient `KETCH_*` variables leak into e2e tests
status: done
created_at: 1792583218
updated_at: 1792583218
---

The sandbox helper removed only the three GitHub token variables, while `Config::load` reads eleven other `KETCH_*` settings from the environment before the sandbox's own `config.toml`. `ketch_with_path` now strips every ambient `KETCH_*` variable, then sets only `KETCH_ROOT` and `KETCH_APPS_DIR`.
