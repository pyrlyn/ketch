---
id: R18
title: One atomic replace helper in `ketch-core`
status: done
created_at: 1792583854
updated_at: 1792583854
---

File replacements already went through `fs_kit::write`. The store swap was still three copies of the same rename: stage as `.incoming`, retire the live tree to `.old`, swap the stage in, and put `.old` back if the second rename fails. That sequence is now `swap_into_store` in `platform/mod.rs`. macOS passes `ditto`; Linux and Windows pass `copy_tree`, and the copy runs only when the rename onto the stage fails. `finalize_store_swap` and `restore_retired_store` moved with it. A payload that is already the store is not retired, and `.old` stays until placement finishes.

Tests: `swap_into_store_of_the_same_path_does_not_retire_it`, `swap_into_store_retires_the_previous_tree_and_restore_puts_it_back`, `swap_into_store_copies_when_the_rename_onto_the_stage_fails`, plus the existing place-failure restore on Linux and macOS.
