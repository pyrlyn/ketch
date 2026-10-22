---
id: K941
title: Run desktop CI once, and only when it can change
status: open
created_at: 1792603908
updated_at: 1792603929
blocked_by:
- K903
- K904
---

`ketch-cs` and `ketch-win-app` both run `scripts/csharp.sh`; `ketch-ffi` and `macos-app` both build the XCFramework; the pipeline's dotnet job reruns `Ketch.AppCore.Tests`; the `--features tui` pass reruns about 250 tests per OS. Six desktop jobs run on every non-docs PR, although the config action already computes a changed-file verdict.

Done when there is one macOS desktop job and one Windows .NET job, a `desktop` output (crates, desktop, scripts, Cargo.lock, mise.toml) turns desktop jobs into reporting no-ops otherwise, and the tui pass runs only tui-dependent tests or one OS.
