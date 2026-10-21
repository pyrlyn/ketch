---
id: D1
title: "`ketch-ffi`: foreign traits and per-operation callbacks"
status: done
created_at: 1792583218
updated_at: 1792583218
---

UniFFI calls callback interfaces "(soft) deprecated" in favour of foreign traits (new in 0.32), and `KetchCore::new` takes the `Reporter` and `Decider` once, while every app wants them per operation so two screens can each watch their own work. Research: `docs/research-desktop-platforms.md`, section 3a, gap G1.

Done when `Reporter` and `Decider` are foreign traits, `install`, `upgrade` and `uninstall` take a reporter, a decider and a `CancelToken` per call, the constructor no longer takes them, the Swift binding test covers a per-call reporter and `stop_processes`, and `crates/ketch-ffi`'s docs say why. A breaking change to the binding, marked as such.

Execution plan (Claude Code / opus-5.5):

1. `crates/ketch-ffi/src/callbacks.rs`: `Reporter` and `Decider` become `#[uniffi::export(foreign)]` traits, held as `Arc<dyn …>`; the adapters onto the core traits stay.
2. `crates/ketch-ffi/src/lib.rs`: `KetchCore::new(root)` only. `install`, `upgrade` and `uninstall` take `reporter`, `decider` and `cancel`, each optional; `uninstall` checks the token before each package. `search`, `outdated`, `changelog` and `doctor` take an optional `reporter`, so their warnings still reach the caller; `root` and `installed` read files and take none. The crate header says why the callbacks are per call.
3. Rust unit tests: two installs on one core, each reporting to its own reporter only; a cancelled uninstall removes nothing.
4. `desktop/macos/KetchCore/Tests/KetchCoreTests/KetchCoreTests.swift`: the existing cases on the new signatures, plus an upgrade through a `ketch-source-test` shell plugin (1.0 then 1.1, a looping shell script as the asset) while the installed binary runs, so a Swift decider receives the holder and declines.
5. Verify: `cargo nextest run`, `cargo clippy --all-targets`, `cargo fmt --check`, `just ffi-test`. Commit as `feat(ffi)!:`.

Status: done. Read-only calls (`search`, `outdated`, `changelog`, `doctor`) also take an optional reporter, so their warnings keep reaching the caller; `uninstall` checks the token before each package (progress and the leftover store folder stay with D2). On macOS the Swift test runs a looping shell script as the held binary: a relocated `/bin/sleep` is killed by the OS.
