---
id: B42
title: "`rstest` and `insta` declared but unused"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`rstest` and `insta` were listed in `[dev-dependencies]` but not used as real test infrastructure: no `rstest` imports, and `insta` snapshot calls had no snapshot files. Removed both crates from `Cargo.toml`, replaced snapshot assertions in `src/resolve.rs` and `src/source/mod.rs` with explicit `assert_eq` checks, and dropped the snapshot helpers from `tests/why.rs`.
