---
id: B48
title: "`registry push` documented as validating like the registry"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`docs/REGISTRY.md` still said push validated the file "exactly as the registry will" without spelling out the per-package checks B18 added, and it described fetching the registry before validation.

The Contributing section now states that `registry push` runs the same per-package checks as CI and `registry validate` (folder name, no `local:` source, `Manifest::validate`), does not scan for name collisions, validates before fetching the registry copy, and drops the old validate-only wording.
