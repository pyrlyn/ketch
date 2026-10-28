---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T12.2. The store

The port of `KetchStore.swift` and `KetchStore.cs` on `Core`: the installed, outdated
(updates and held) and search state, install, upgrade and uninstall as one running operation
with per-package stage and download progress, cancel, `busy` with Retry, `cancelled` logged
and not alerted, other errors as an alert in the core's wording, the binary-choice, stop and
leftover questions, the log (500 entries), search after a typing pause where only the newest
answer counts, changelog and doctor. The Rust apps' store and contract tests are ported against
`FakeCore`.

## Plan

1. `desktop/src/main/kotlin/ketch/desktop/store/`: `StoreState` (an immutable snapshot the screens
   read through a `StateFlow`: installed, outdated with `updates`/`held`, search results, checks,
   activity, log, busy, error, pending question, last checked) and `KetchStore` on `Core`.
   - StateFlow rather than Compose state, so the store depends on kotlinx only.
   - Operations run one at a time; a second is refused with an alert (the macOS app's choice).
   - Events and questions go through one channel the store drains on its own scope, so they
     land in the order the core sent them and before the call's result.
   - `Progress` maps to its package through the `Download` task its `Began` opened.
   - Held is `pinned` or `held_by`: the Kotlin wire carries both, and `upgrade` skips either.
2. Tests in `desktop/src/test/kotlin/ketch/desktop/store/` with kotlinx-coroutines-test:
   `ContractScenarioTests` (macOS and Windows) and the `KetchStoreTests` that apply to the
   scenario fake (busy and retry, cancel at a step and with a question open, the binary choice,
   the stop question, a second operation, newest search wins, typing pause, doctor count,
   download share, 500-entry log). Tests that need the old hand-made fake's sample registry
   are rewritten against the scenarios.
3. Not here: settings and prerelease (T12.4), `ketch://` links and changelog rendering (T12.3).

Check: `./gradlew build` green.
