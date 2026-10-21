---
id: B111
title: Discover's empty search errors on the real core
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: `ketch-ffi` defines what `search("")` returns, both fakes agree, and a contract scenario covers it. Plan: decide in `crates/ketch-ffi/src/lib.rs` (browse the registry), align `FakeKetchCore` in Swift and C#, add a scenario under `desktop/contract/scenarios`.
Model: Claude Code / opus-5.5 · Status: done, merged in #313 · Priority: P2 · Complexity: 3
Check: the ffi unit test and the scenario.
