---
status: todo
priority: P0
complexity: 4
readiness: 0%
agent:
---

# T6. Platform backends, processes, shell

`platform` (`mod`, `unix`, `macos`, `linux`, `claim`), `process` (busy files, stopping
processes), `shell` (PATH setup, completions install). POSIX through cinterop; macOS specifics
(quarantine, `/Applications`, codesign checks) as in Rust.
