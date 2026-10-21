---
id: R15
title: Drop the unread placement version and registry alias list
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row R15 in the 2026-10-08 findings. `Placement::version` was carried into every place call and never read. `Resolver::aliases` had no caller.

Done: both are gone. The root `dirs` dependency, `Release::asset` and the stale `allow(dead_code)` on `PackageSpec::raw` were already gone.
