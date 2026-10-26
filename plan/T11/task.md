---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T11. `ketch serve` and the desktop protocol

A JSON-lines mode of the binary with the `ketch-ffi` surface: one call per operation
(`installed`, `search`, `outdated`, `install`, `upgrade`, `uninstall`, pin, rollback, prune,
changelog, info, history, doctor, path, registry refresh, config), events, questions
(`choose_binary`, `stop_processes`) and cancellation. Records live in `:ketch-wire`; the
`desktop/contract/scenarios` files replay against it.
