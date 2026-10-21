---
id: B18
title: "`registry push` skips registry extra checks"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`push` stopped at `Manifest::deserialize` + `Manifest::validate`, while `registry::read_package` also refused a `local:` source and a `name` that disagreed with the folder — so a manifest could open a pull request that registry CI then rejected.

Extracted `registry::validate_registry_entry` (name/folder agreement and no `local:` source) and call it from both `read_package` and `push::load`. Added unit tests in `push.rs` and integration tests in `tests/registry_push.rs` for both refusal cases.

Shipped work moved out of `plan.md`. `CHANGELOG.md` is the release record.
