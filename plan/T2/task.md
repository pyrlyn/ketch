---
status: in progress
priority: P0
complexity: 2
readiness: 70%
agent: Claude Code / claude-opus-5-5
---

# T2. Pure core

The parts of `crates/ketch-core` with no IO and few dependencies: `error`, `text`, `diff`,
`cancel`, `decide`, `bin_choice`, `platform/scoring` (asset scoring, needs the `model` types it
reads). Their unit tests ported and green.

## Plan

1. `ketch.core.error.KetchError` (sealed, Rust `Error`): messages, `details`, `hint`, exit codes.
2. `ketch.core.text` (`bytes`, `truncate`, Rust `str::lines`, Rust byte order for sorting),
   `ketch.core.diff.unified`, `ketch.core.cancel.Cancel`, `ketch.core.decide.Decider`,
   `ketch.core.binchoice`.
3. `ketch.core.model` `Os`, `Arch`, `TargetSpec` (+ native `host()`); `ketch.core.platform`
   `AssetScore`, the asset-name constants, `orderDiscoveredExecutables`, the three scorers.
4. Port every Rust unit test of these files (the `fs_kit` error test waits for `fsio` in T4).
5. Check: `./gradlew :ketch-core:build` (tests, ktlint, detekt) green.

Written; not yet built after the last fixes (step 5 pending).
