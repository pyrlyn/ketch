---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T12.2. The store

The port of `KetchStore.swift` and `KetchStore.cs` on `Core`: the installed, outdated
(updates and held) and search state, install, upgrade and uninstall as one running operation
with per-package stage and download progress, cancel, `busy` with Retry, `cancelled` logged
and not alerted, other errors as an alert in the core's wording, the binary-choice, stop and
leftover questions, the log (500 entries), search after a typing pause where only the newest
answer counts, changelog and doctor. The Rust apps' store and contract tests are ported against
`FakeCore`.
