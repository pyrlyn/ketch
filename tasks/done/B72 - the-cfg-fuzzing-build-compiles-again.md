---
id: B72
title: The `cfg(fuzzing)` build compiles again
status: done
created_at: 1792583218
updated_at: 1792583218
---

Since the R5 workspace split, `RUSTFLAGS="--cfg fuzzing" cargo check -p ketch` fails with ~94 errors, so no `fuzz/` target builds. `src/lib.rs` still declares `mod cmd` (whose `crate::` paths need every core module and the binary-only `ui`, `complete`, `man`, `self_docs`), re-exports a `ketch_core::ui` that does not exist, calls `extract_auto` without the `Report` it now takes, and uses `walkdir`, which the root crate does not depend on. The core's fuzz helpers (`lockfile::fuzz_parse`, `manifest::fuzz_parse_registry`, `hooks::fuzz_shell`, `source::plugin::fuzz_parse`, `source::github::fuzz_parse_digest`, `parse_checksum_file`) are `pub(crate)`, so the binary's fuzz library cannot call them.

Done when `cargo +nightly fuzz build` builds every target, `RUSTFLAGS="--cfg fuzzing" cargo check -p ketch -p ketch-core` is clean on stable, the normal public surface of `ketch-core` is unchanged (fuzz-only items exist only under `#[cfg(fuzzing)]`), and `just check` plus CI run that check so it cannot rot again.

Plan:

1. `src/lib.rs`: drop `mod cmd` (no target needs it; `cli.rs` does not use it), re-export only the core modules `cli.rs` and `fuzzing` reach, call `changelog::sanitize` (what `ui::printable` wraps) instead of `ui::printable`, pass a silent `Report` to `extract_auto`; update the header comment.
2. `crates/ketch-core`: make the `#[cfg(fuzzing)]` helpers `pub`, and add a `#[cfg(fuzzing)] pub` wrapper for `parse_checksum_file`, which stays `pub(crate)` in normal builds.
3. `Cargo.toml`: `walkdir` as a `[target.'cfg(fuzzing)'.dependencies]` entry, so normal builds do not gain it.
4. `Justfile`: `fuzz-check` recipe (`RUSTFLAGS="--cfg fuzzing" cargo check --locked -p ketch -p ketch-core`), called from `check`; the same step in the macOS CI job.
5. Verify: the stable check, `cargo +nightly fuzz build`, a short `just fuzz <target> 5` smoke run, then `just check`.

Fixed in pyrlyn/ketch#268: `src/lib.rs` compiles only `cli.rs` beside the core modules the targets reach, the core's fuzz helpers are `pub` only under `cfg(fuzzing)` (plus a fuzzing-only `fuzz_parse_checksum_file` wrapper), `walkdir` is a `cfg(fuzzing)` target dependency, and `fuzz/Cargo.lock` is refreshed. `just fuzz-check` runs in `just check` and in CI's macOS `package` job. All 11 targets build with `cargo +nightly fuzz build` and ran clean for 5 s each.

Status: done.
