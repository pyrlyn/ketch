---
id: R4
title: Fuzz testing with cargo-fuzz / libFuzzer
status: done
created_at: 1792583218
updated_at: 1792583218
---

Plan:
1. Library target: ketch is binary-only (no `src/lib.rs`), so the fuzz crate cannot reach the parsers. Add `src/lib.rs` holding the modules, and make `main.rs` a thin caller. Keep `unsafe_code = "forbid"`. Alternatively, a `#[cfg(fuzzing)]` `fuzzing` module with entry points, as rtok does.
2. `fuzz/`: a standalone cargo-fuzz workspace excluded from the root one (`cargo-fuzz = true`, `libfuzzer-sys` 0.4, `arbitrary`), the same shape as rtok's `test/cargo-fuzz` branch.
3. Targets: `cli_argv` (`Cli::try_parse_from` over arbitrary argv); `package_spec` (`PackageSpec::parse`); `manifest_toml` (`manifest::parse_registry` + `Manifest::validate`); `lockfile` (parse + validate); `state` (state file load from bytes); `checksum_file` (`source::github::parse_checksum_file`, `parse_digest`); `archive_extract` (tar.gz, tar.bz2, tar.xz and zip through `extract` into a temp dir, asserting every written path stays under the root through `safe_member_path`); `extra_paths` (classification in `src/extra.rs`); `plugin_protocol` (the `ketch-source-*` JSON in `src/source/plugin.rs`); `hook_line` (Windows hook-line quoting, `src/hooks.rs`); `printable` (`ui::printable` filtering). Seed corpora come from `tests/fixtures`.
4. Verify: `cargo +nightly fuzz build` for every target, then a short run of each (`cargo +nightly fuzz run <target> -- -max_total_time=60`). Every crash becomes a minimized regression test in `tests/`, with the fix in its own PR.
5. Optional CI: a non-required nightly job (build plus a short run) on Linux. Do not touch `dependabot.yml` or `sync-docs.yml`.
6. Deliver as a PR; do not merge it.

Execution plan (Claude Code / opus-5.5):
1. `src/lib.rs` compiled only under `cfg(fuzzing)` (empty crate on stable), re-declaring the same modules as `main.rs` plus a `fuzzing` module of entry points; `main.rs` is not touched. Private items the targets need get `#[cfg(fuzzing)]` wrappers in their own module. `unexpected_cfgs` learns `cfg(fuzzing)` in `Cargo.toml`.
2. `fuzz/` with its own `[workspace]`, one `fuzz_targets/<target>.rs` per target above, `fuzz/seed.sh` building seed corpora from `tests/fixtures`, `ketch.toml`, `src/builtin.toml` and archives it makes on the fly.
3. `cargo-fuzz` pinned in `mise.toml`; nightly stays a rustup toolchain used only by `cargo +nightly fuzz`. `just fuzz` recipe, rows in `toolchain.md` and `rust.md`.
4. Verify: stable `cargo fmt`/`clippy`/`nextest` clean, `cargo +nightly fuzz build`, 60 s per target; any crash gets a regression test in `tests/`, not a fix.

Done in the pull request: all eleven targets build and ran 60 s each without a crash in ketch. The optional nightly CI job is not added.

Check: `cargo +nightly fuzz build` succeeds for all targets; each target runs 60 s with no crash (or the crash is filed with a repro test); stable `just check` does not compile `fuzz/`.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/177. All eleven targets build and ran 60 s each without a crash in ketch. The optional nightly CI job was not added.
