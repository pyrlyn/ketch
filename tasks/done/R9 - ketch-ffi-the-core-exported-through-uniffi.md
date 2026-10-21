---
id: R9
title: "`ketch-ffi`: the core exported through UniFFI"
status: done
created_at: 1792583218
updated_at: 1792583218
---

A `ketch-ffi` crate in the workspace wraps `ketch-core` with UniFFI (proc-macro mode, `uniffi::setup_scaffolding!()`). The generated scaffolding is `extern "C"`, so this crate alone relaxes `unsafe_code` from `forbid` to `deny` with the generated module allowed, and says why in its `//!` header; `ketch-core` and `ketch` keep `forbid`. The surface is coarse: operations (list, search, install, upgrade, uninstall, changelog, doctor), plain records for results, a callback interface for R6's reporter and R7's decider, a typed error enum, cancellation. It builds an XCFramework for both macOS architectures, generates Swift bindings, and has a Swift test that runs one operation against a scratch `KETCH_ROOT`. The binding stays language-neutral so the Windows front end can reuse it later.

Plan:
1. Crate `crates/ketch-ffi`: `crate-type = ["lib", "staticlib"]`, `publish = false`, `dist = false`; `uniffi` at the latest version at start (0.32.2 on 2026-09-30), added to `toolchain.md` and `rust.md`. Check first whether `unsafe_code = "deny"` plus the generated code compiles, or whether the lint must be `allow` for this crate; record the answer in the header.
2. API, one object: `KetchCore::new(root: Option<String>)` builds `Config` per call (R8). Methods (sync; Swift calls them off the main actor): `installed() -> Vec<InstalledPackage>`, `search(query) -> Vec<RegistryPackage>`, `outdated() -> Vec<Upgrade>`, `install(spec, options)`, `upgrade(names)`, `uninstall(names)`, `changelog(name, from, to) -> String` (already sanitized by `changelog::sanitize`), `doctor() -> Vec<Finding>`. Records are FFI-only mirror types converted from `model.rs`, so the core keeps no UniFFI attributes.
3. Callbacks: `#[uniffi::export(callback_interface)]` `Reporter { fn event(e: Event) }` and `Decider { fn choose_binary(package, candidates) -> Option<u32> }`, bridged to the R6/R7 traits; a `CancelToken` object wrapping R8's token.
4. Errors: `#[derive(uniffi::Error)] enum KetchError { Busy { pid }, Cancelled, NotFound { name }, Network { message }, Verification { message }, Other { message } }` mapped from `crate::error::Error`.
5. Build script `scripts/xcframework.sh` (`just xcframework`): `cargo build --release -p ketch-ffi` for `aarch64-apple-darwin` and `x86_64-apple-darwin` with `MACOSX_DEPLOYMENT_TARGET=26.0`, `lipo` into one static lib, `uniffi-bindgen generate --library … --language swift`, `xcodebuild -create-xcframework`, output into a local Swift package `desktop/macos/KetchCore/` (Package.swift, `platforms: [.macOS(.v26)]`). Generated sources and the XCFramework are build output, gitignored.
6. Rust tests for the conversions and error mapping; a Swift test (`swift test` in the package) that installs a fixture package from the local source into a scratch root, with a recording reporter.
7. CI: a macOS job building the XCFramework and running `swift test`; not part of the CLI release.

Check: `just xcframework` builds on a clean checkout; `swift test` passes; `cargo clippy --workspace --all-targets` clean; `grep unsafe crates/ketch-core src` still empty.

Execution plan (Claude Code / opus-5.5), after surveying the merged R6/R7/R8 code:

1. `crates/ketch-ffi` as above, with uniffi 0.32.2 (latest on crates.io, 2026-09-23). It inherits `[workspace.lints]` with `unsafe_code` still `forbid`: UniFFI's generated `unsafe` comes from external-macro expansions, which rustc does not lint, so no relaxation is needed (the reason is in its `//!` header); the `uniffi-bindgen` binary sits behind a `bindgen` feature so the static library does not compile the generator.
2. `ketch doctor`'s checks live in the binary (`src/cmd/system.rs`), out of an FFI's reach: move the check gathering into a core `doctor` module, unchanged; the command keeps `--fix` and the rendering. A `TaskId::get` accessor in `report.rs` so events cross the boundary with their ids.
3. `KetchCore` methods build `Config` and call `log::init` per operation, take `state::Lock` for the mutating ones, and compose existing core calls (`listing`, `Resolver::search`, `install::batch`/`uninstall`/`latest_release`, `changelog`); no install logic in the FFI crate. Each mutating call takes an optional `CancelToken` that is passed into every `InstallRequest`.
4. Reporter and decider are callback interfaces handed to the constructor; Rust adapters implement the core traits.
5. `scripts/xcframework.sh` + `just xcframework` + `just ffi-test`: both Darwin targets (`rustup target add` for the missing one), `lipo`, bindings from the arm64 library, `xcodebuild -create-xcframework` into `desktop/macos/KetchCore/` (gitignored output, committed `Package.swift` and Swift test). The Swift test installs a `local:` fixture into a scratch root.
6. CI: one macOS job (`ketch-ffi`) running what `just ffi-test` runs, on every gate run: almost any core change can change the bindings, and a path filter would need another action.

Creator decisions (2026-10-01, from R10's `docs/research-desktop-windows-linux.md`):

- Keep UniFFI 0.32.x, the latest. Whether the Windows front end gets C# bindings from a third-party generator or another route is decided later, with that front end.
- `ketch-ffi` also builds as a `cdylib` beside the `staticlib`, so a later Windows front end can load `ketch_ffi.dll`. The XCFramework still wraps the static library.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/208. Surface: `KetchCore` (installed, search, outdated, install, upgrade, uninstall, changelog, doctor), records, `Reporter`/`Decider` callbacks, `CancelToken`, `KetchError`. `changelog` returns a structured `Changelog` for one version rather than a from–to string; the SwiftUI adapter maps it in F12.
