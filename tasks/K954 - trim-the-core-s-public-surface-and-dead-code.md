---
id: K954
title: Trim the core's public surface and dead code
status: open
created_at: 1792603924
updated_at: 1792603924
priority: 3
---

33 of 38 core modules are `pub`, and 167 of 510 public items are named nowhere outside the core; `allow(dead_code)` keeps `Platform::id`, `app_bundle_extension`, `ReleaseSelection::rejected`, `SourceRegistry::schemes` and `Config::config_file` alive, and `ketch why` hard-codes `rejected: Vec::new()`. AGENTS.md's "everything else stays `pub(crate)`" has drifted. Extends R15.

Done when the flagged items are `pub(crate)` or gone, `rejected` is wired into `ketch why` or deleted, and `unreachable_pub` warns in the core where privacy allows.
