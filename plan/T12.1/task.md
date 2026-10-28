---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T12.1. The app's core: `ketch serve` client and the scenario fake

Everything the app asks the core goes through one interface. Its real implementation runs the
native `ketch serve` and speaks `:ketch-wire` with it. Its fake replays
`desktop/contract/scenarios`, the way the Rust apps' fakes do (`FakeKetchCore.swift`,
`FakeKetchCore.cs`). Done when the store (T12.2) can be written against the interface alone.

## Plan

1. `desktop/src/main/kotlin/ketch/desktop/core/`:
   - `Core`: `call(call, callbacks, cancel)` suspends until the result; it returns the value
     as JSON with a typed helper over `resultSerializer`, and throws `CoreException` carrying
     the wire `KetchError`.
   - `Callbacks`: events and questions per call.
   - `CancelToken`.
   - `ServeCore`: runs `ketch serve [--root DIR]` and checks `hello`'s protocol. It routes
     events, asks and results by call id, answers a question once the app has, and sends
     `cancel`. When the child dies, every open call fails with what it printed on stderr.
   - `locateKetch`: `KETCH_EXE`, else the binary in the packaged app's resources directory
     (`compose.application.resources.dir`).
2. `desktop/src/test/`:
   - `FakeCore`: reads come from their scenario's value. Install, upgrade and uninstall replay
     the script, stop with `Cancelled` when cancelled between steps, and change the installed
     list the fake answers.
   - Tests: every scenario replays through the fake. `ServeCore` runs against the built native
     `ketch` on a scratch root: hello, a local install with its events, a question answered
     (the leftover folder), a cancel, the child dying.
3. Gradle: the desktop tests depend on the host's debug `ketch` link task and get its path as
   `KETCH_EXE`.

Files: `desktop/build.gradle.kts`, `desktop/src/`. Check: `./gradlew :desktop:test`, `./gradlew build`.
