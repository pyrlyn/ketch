---
id: B87
title: Local tree hash collides and skips symlinks and modes
status: done
created_at: 1792583854
updated_at: 1792583854
---

`sha256_tree` used to hash `path \0 bytes` for regular files only. `a` = `X` plus `b` = `Y` was the same digest as one file `a` = `Xb\0Y`, a symlink was invisible, and the exec bit was ignored, so a lockfile could accept a different `.app`. Each file and symlink is now one record: kind, path length, path, permission bits, payload length, and either the file bytes or the symlink's own target. A lock written before this framing does not match an unchanged bundle; `ketch lock` records the new digest. Noted in `docs/LOCKFILE.md`.

Tests: the two-file layout no longer matches the fused file; on Unix a mode change and a retargeted symlink each change the digest, and a symlink does not hash as a regular file with the target's text.
