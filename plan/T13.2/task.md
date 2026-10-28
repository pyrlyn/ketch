---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T13.2. ketch-core on mingwX64

`platform/windows.rs` (the store, links and `.exe` shims, busy files moved aside, the user PATH
in the registry, PowerShell profiles and AutoRun), the Windows parts of `shell`, `process`,
`self_update`, `hooks`, `state`, `doctor` and `extract`, with every Rust test of them. Done when
`:ketch-core` builds for mingwX64 and its tests pass on the Windows VM.
