---
id: D4
title: Contract fixtures for every app's fake core
status: done
created_at: 1792583218
updated_at: 1792583218
---

Three apps each test against a fake core; if each fake invents its own records and event streams, they will drift from the real one and from each other. Research: section 2, "Written once".

Done when a set of language-neutral JSON scenarios (records, event streams with progress and `Abandoned`, `Busy`, `Cancelled`, decisions) is generated from the Rust types by a test that fails on drift, and the macOS app's fake core reads them; the Windows and Linux fakes read the same files when they exist.

Execution plan:

1. `ketch-ffi`: derive `serde::Serialize` on the exported records, `Event`, `TaskKind`, `Stage`, `Holder` and `KetchError` (internally tagged by `type`, snake_case), so the wire shape is the Rust shape.
2. `crates/ketch-ffi/tests/contract.rs` builds each scenario from those Rust values (a call, an ordered script of events and decisions, an outcome) and compares the JSON with `desktop/contract/scenarios/*.json`; `KETCH_BLESS=1` rewrites them, as the schema drift tests do. Scenarios: every read, install (progress, `Abandoned`, binary choice), upgrade (stopping processes), uninstall, and each error (`Busy` with and without a pid, `Cancelled`, `NotFound`, `Network`, `Verification`, `Other`).
3. macOS: `Ketch/Core/ContractScenario.swift` decodes them and maps events and records to the app's types; `FakeKetchCore` replays a scenario for reads and for install, upgrade and uninstall. Store tests run Busy, Cancelled, a network failure, a binary choice and a normal install from the files, and every file must decode.
4. `desktop/contract/README.md` states the file format for the Windows and Linux fakes, which read the same files once they exist.
5. Verify: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`, `just ffi-test`, `just macos-test`, `swift-format lint --strict`.
