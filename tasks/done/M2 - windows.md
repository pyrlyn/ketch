---
id: M2
title: Windows
status: done
created_at: 1792583854
updated_at: 1792583854
---

`src/platform/windows.rs`, `tests/install_windows.rs`. Copies into the bin dir and records `CopiedFile`. `ketch path install` on Windows writes the user PATH. `install.sh` fetches the host tarball; `release.yml` publishes Windows tarballs; CI runs the Windows suite on Windows.
