---
id: R20
title: File and directory helpers from `fs-kit`
status: done
created_at: 1792583854
updated_at: 1792583854
---

The creator ordered one entry point for file work. `fs-kit` (path identity, atomic writes, tree copy and removal, backups) replaces ketch's own copies: `dunce` and `file-backup` are dropped, the two `remove_any` copies on Unix and in `self_update.rs` are gone, `src/lib.rs` `lexical_join` is `fs_kit::path::normalize`, and the temp-file-plus-rename writers in `state.rs`, `lockfile.rs`, `listing.rs`, `manifest.rs` and `shell.rs` go through `fs_kit::write`. Done means: no `dunce`, no `file-backup`, and every behavior listed below as kept local has a reason on this card.

Plan: (1) add `fs-kit` once under `[workspace.dependencies]`, as a path dependency until it is on crates.io, then `fs-kit = "0.1"` from crates.io with the `just setup` `paths` override for local work (0.1.0 is published); (2) swap canonicalize and simplify calls; (3) swap `remove_any`, `lexical_join` and the atomic writers, with `fsio::write_private` keeping the `0600` and replace-the-link behavior the tempfile writers had; (4) run fmt, clippy and the whole test suite.

Status: done (#294). No direct `dunce` or `file-backup` dependency remains; path identity, atomic writes, tree copy and removal go through `fs-kit`.

Kept local on purpose: `source/local.rs` `copy_tree` (its Windows branch materialises links through `materialize_local_symlink`, with its own missing-target error, where `fs-kit` would create links when Developer Mode allows); `http.rs` download (a streamed write with a cancel check); the store-swap and registry-swap renames; `extract/macos.rs` `copy_tree` (shells out to `ditto`). `platform/windows.rs` `remove_any` and `extract/archive.rs` `copy_dir_contents` moved once `fs-kit` kept the OS error code (`fs_kit::raw_os_error`) and gained `Links::Skip`; the Windows half is checked by Windows CI only. Overlaps R12 (`remove_any` duplicate) and R18 (the rename-over-temp copies).
