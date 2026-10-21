---
id: D10
title: "Windows: C# binding for `ketch-ffi`"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The Windows app is C# + WinUI 3 over `ketch-ffi` (creator, 2026-10-01). `uniffi-bindgen-cs` last released for UniFFI 0.31.0; `ketch-ffi` is on 0.32.2. Decided by the creator (2026-10-01, open decision 4): first try the generator built from PR #176 (UniFFI 0.32.0), pinned to commit `0fc022aa1d73fb1dda91a778b63f2824d7dca58b`; if it fails against 0.32.2, use the C ABI from D15 through `LibraryImport`. Downgrading `ketch-ffi` to UniFFI 0.31.2 is not an option. Research: section 4.

Done when the chosen route generates C# for `ketch-ffi`, the generator is pinned (a commit or a version, no system install), a .NET 10 test calls `installed`, `doctor` and a cancelled install against a scratch root on Windows CI, and the route taken and why is in the research page.

Execution plan (Claude Code / opus-5.5):

1. Route B: pin the generator in `mise.toml` as `cargo:` from `dennisameling/uniffi-bindgen-cs` at `0fc022aa1d73fb1dda91a778b63f2824d7dca58b`, and the .NET 10 SDK beside it, so nothing is installed system-wide.
2. `scripts/csharp.sh` (`just csharp`): build `ketch-ffi`'s shared library, generate `ketch_ffi.cs` from it with `desktop/windows/uniffi.toml` (public types, a `Ketch.Ffi` namespace), and put both in `desktop/windows/KetchCore/Generated/`, which is not committed, as the Swift bindings are not.
3. `desktop/windows/KetchCore` (a `net10.0` class library over the generated file and the native library) and `desktop/windows/KetchCore.Tests` (MSTest): `installed` on a scratch root, `doctor`, and a cancelled install that throws `Cancelled` and places nothing.
4. CI: a `ketch-cs` job on `windows-latest` that installs the pins through mise and runs `just csharp-test`'s steps.
5. Research page section 4: the route taken and why; `toolchain.md` and the AGENTS.md layout rows.
6. If the generator fails against 0.32.2: fall back to D15's C ABI through `LibraryImport`, and say so in the research page.

Status: done. Route B, the generator from PR #176 at the pinned commit, works against `ketch-ffi` on UniFFI 0.32.2 without a change to the generator; routes C and D were not needed. Three adjustments, recorded in the research page: `TaskKind::Download`'s `batch` field became `batch_id`, because a C# property named `Batch` clashes with the inherited `Batch` variant (a breaking change to the binding); `desktop/windows/uniffi.toml` makes the generated types public; and `scripts/csharp.sh` uses the mise install of the generator and checks its version, because an older `uniffi-bindgen-cs` in `~/.cargo/bin` shadows it on `PATH`. The MSTest project also covers `ketch_version` and an install reported to a C# `Reporter`.
