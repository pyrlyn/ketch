---
status: todo
priority: P0
complexity: 2
readiness: 0%
agent:
---

# T2. Pure core

The parts of `crates/ketch-core` with no IO and few dependencies: `error`, `text`, `diff`,
`cancel`, `decide`, `bin_choice`, `platform/scoring` (asset scoring, needs the `model` types it
reads). Their unit tests ported and green.
