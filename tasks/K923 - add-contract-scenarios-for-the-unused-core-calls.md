---
id: K923
title: Add contract scenarios for the unused core calls
status: open
created_at: 1792603875
updated_at: 1792603875
priority: 1
---

`ketch-ffi` exports 19 calls; the apps and `desktop/contract` cover 8. `pin`, `unpin`, `rollback`, `prune`, `history`, `info`, `path_status`, `path_install`, `doctor_fix` and `registry_refresh` have no scenarios, so no fake can show a screen for them.

Done when `crates/ketch-ffi/tests/contract.rs` generates scenarios for each (rollback: ok, nothing to roll back to, binary choice; path install: dry run), and all three fakes decode them.
