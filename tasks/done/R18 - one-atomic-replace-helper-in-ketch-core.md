---
id: R18
title: One atomic replace helper in `ketch-core`
status: done
created_at: 1792583218
updated_at: 1792583218
---

Done. File writes go through `fs_kit::write` (`replace_file` in `manifest.rs`, and the same helper the other file writers already used). The store swap is one `swap_into_store` in `platform/mod.rs`: stage beside the destination, retire the live tree to `.old`, and swap. Each backend passes its own copy (`ditto` on macOS, `copy_tree` on Linux and Windows) for the cross-filesystem case. `finalize_store_swap` and `restore_retired_store` live next to it. Behaviour is unchanged: `.old` stays until links succeed, and a path that is already the store is not retired.
