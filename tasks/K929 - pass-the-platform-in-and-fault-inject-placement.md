---
id: K929
title: Pass the platform in, and fault-inject placement
status: open
created_at: 1792603882
updated_at: 1792603882
---

14 direct `platform::host()` calls (7 in `install.rs`) mean `FakePlatform` only reaches asset choice. The riskiest code, commit and rollback after a failed placement (B79, B104, B123), can only be tested on each OS.

Done when the platform travels in `Ctx` (or a pipeline struct, defaulting to `host()`) and a fault-injecting fake proves on every host that a link failure after the store swap restores the store and the old links.
