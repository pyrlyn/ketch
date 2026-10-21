---
id: B62
title: A `bin` glob matching several payload files links the helper stub, not the binary
status: done
created_at: 1792583854
updated_at: 1792583854
---

Observed 2026-09-26: `ketch install pyrlyn/rtok` on Windows linked the 390 KB `rtok-hook.exe` dist ships beside the 34 MB `rtok.exe` into `~/.ketch/bin/rtok.exe`, and every `rtok` invocation then hung reading stdin for a hook payload. `resolve_bin_specs` takes the FIRST walkdir match of a glob; the registry entry's `bin = [{ path = "rtok*", name = "rtok" }]` also matches `rtok-hook.exe`, and NTFS lists `rtok-hook.exe` ahead of `rtok.exe`. Unix readdir order happens to keep `rtok` first, so only Windows broke — by luck, not design. No `*`/`?` pattern matches `rtok`+`rtok.exe` while excluding `rtok-hook.exe`, so manifests cannot spell their way out.

Plan: `glob_preferred` in `model.rs` — among a glob's matches, prefer the file whose stem equals the spec's link `name` (case-insensitive, like `glob_match`), falling back to the first match — used by `resolve_bin_specs` on Windows and Unix; `name`-only specs keep the extension-aware discovery they already have. Follow-up once released: rtok's entry can also link the hook client deliberately (`{ path = "rtok-hook*", name = "rtok-hook" }`) so hosts get the fast `rtok-hook` on PATH.

Check: unit tests for `glob_preferred` (stem preference beats directory order, case-insensitive, first-match fallback, empty is None) and one `resolve_bin_specs` test per platform over a payload holding `rtok(.exe)` beside `rtok-hook(.exe)`; `just check` green; e2e — a locally built ketch installs pyrlyn/rtok into a scratch `--root` and `bin/rtok.exe` is the 34 MB binary answering `--version`.

Result: All green 2026-09-26. Unit tests in model.rs and platform/{windows,unix}.rs; the unix test compiles on the unix runners (the module is cfg'd out on Windows). `just check` passed on Windows in full: fmt-check, clippy, 474/474 nextest, lint-commits, lint-shell, dist-check, package (dist tarball built and checksum-verified), lint-cask skipped as macOS-only. E2E: the locally built ketch installed rtok v0.9.0 into a scratch root and `bin/rtok.exe` (34,342,912 bytes) answered `rtok 0.9.0 (c60f3694b)` through the unchanged `rtok*` registry entry.

Status: done 2026-09-26
Model: ZCode / glm-5.3
