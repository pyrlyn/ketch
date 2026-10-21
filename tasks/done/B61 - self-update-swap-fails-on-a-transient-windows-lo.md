---
id: B61
title: "`self update` swap fails on a transient Windows lock"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`replace_binary` retries a short antivirus lock. `io_retry` pauses 100, 150, 200, then 250 ms and gives up. It wraps the rename of the running binary aside, the copy of the new binary into place, and the restore rename/remove. Only `PermissionDenied` and Windows os errors 5 and 32 are retried; any other error returns on the first attempt. The success-path removal of the `.old` image is not retried: that file is the running image until this process exits, and B60 sweeps it on the next self command. Noted in `docs/TROUBLESHOOTING.md`.

Tests: a `PermissionDenied` that clears on the third call, a `NotFound` that returns with no pause, a lock that never clears and stops after the four pauses, and raw errors 5 and 32 counting as transient. Verified on macOS: `cargo clippy --all-targets --locked -- -D warnings` clean, `cargo nextest run --locked --all-targets` 588 passed. The Windows-only probe cases run in CI.
