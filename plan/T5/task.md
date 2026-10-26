---
status: todo
priority: P0
complexity: 4
readiness: 0%
agent:
---

# T5. Archive extraction

`extract` (`mod`, `archive`, `macos`): tar, gzip, xz, bzip2, zip, `.dmg` and `.pkg` on macOS,
with every guard the Rust code has (path traversal, links out of the tree, size and member
limits, unsupported formats refused). Codecs come from maintained libraries or system C
libraries through cinterop; the choice is recorded in `plan/README.md`.
