---
status: in progress
priority: P2
complexity: 4
readiness: 0%
agent: Claude Code / claude-opus-5-5
---

# T13. Windows

mingwX64 target, `platform/windows`, PATH in the registry, `.exe` shims, Windows completions and
the Windows end-to-end tests.

## Subtasks

The creator split T13 when it was claimed.

| Id | What |
| --- | --- |
| T13.1 | kotlin-kit on mingwX64: fs, process, archive, http and SQLite for Windows |
| T13.2 | ketch-core on mingwX64: `platform/windows`, `shell`, `process`, `self_update` and the rest of Rust's `cfg(windows)` code |
| T13.3 | The command line on Windows: PowerShell and cmd completions, `system`, the Windows end-to-end tests, `ketch.exe` in the MSI |

T13 is done when all three are.
