# CI platforms

Principle: everything that can be built, checked or tested on Linux runs on Linux
(`ubuntu-latest`, plus `ubuntu-24.04-arm` for the aarch64 target). Windows and macOS runners
keep only what exists or matters only on that platform. Those jobs are optional
(`continue-on-error`), so they never fail the `ci` workflow. No platform test run was removed:
each one moved to the job of its platform.

The shared checks come from [pyrlyn/ci](https://github.com/pyrlyn/ci) through
`.github/workflows/pipeline.yml`.

## Gate

`pipeline / gate` is pyrlyn/ci's gate job and is meant to be the only required check. It covers
pyrlyn/ci's own jobs (config, CodeQL, Semgrep, actionlint, license, commits, no-pinned-ci, ...),
not this repository's `ci.yml` jobs. It fails only when a needed job's result is `failure`:
`skipped` and `cancelled` pass. There is no local gate job.

Until the `protect-main` ruleset is reduced to `pipeline / gate`, it still lists
`rust / aarch64-apple-darwin`, `rust / x86_64-pc-windows-msvc` (no longer produced: the `rust`
matrix is Linux only) and the optional platform jobs (`powershell`, `macos-app`, `ketch-ffi`,
`package (macos-latest, ...)`, `package (windows-latest, ...)`).

## Per job

| Job | Platform | Removed from Windows/macOS | Kept there, and why |
|-----|----------|----------------------------|---------------------|
| `rust / fmt`, `rust / x86_64-unknown-linux-gnu`, `rust / aarch64-unknown-linux-gnu` (clippy, check, full nextest suite, `tui` feature) | Linux | the `aarch64-apple-darwin` and `x86_64-pc-windows-msvc` matrix entries (full suite, clippy, check) | nothing |
| `windows` | Windows, optional | - | the `cfg(windows)` tests (45), picked by `PLATFORM_TESTS` |
| `macos` | macOS, optional | - | the `cfg(target_os = "macos")` tests (100), picked by `PLATFORM_TESTS` |
| `powershell` | Windows, optional | - | `install.ps1` under Windows PowerShell 5.1 and pwsh |
| `macos-app`, `ketch-ffi` | macOS, optional | - | Xcode build and tests, the XCFramework and `swift test` |
| `ketch-cs`, `ketch-win-app` | Windows, optional | - | the C# bindings over `ketch_ffi.dll`, .NET tests and the WinUI build (see Known gaps) |
| `package (ubuntu-latest, ...)` | Linux | the shell/Python script checks and the `cfg(fuzzing)` check, which ran on every entry or on macOS | release-shape checks |
| `package (macos-latest, ...)`, `package (windows-latest, ...)` | macOS / Windows, optional | the script checks above | `dist build` of the native tarball; `brew style` on the cask (Homebrew is macOS-only here) |
| `config`, `commits`, `macos-runner`, `ketch-capi`, `ketch-linux-app`, `design`, `hakari`, `review` | Linux | none (never ran elsewhere) | nothing |
| `pipeline / *` | Linux (pyrlyn/ci defaults) | - | nothing |

## Keeping the platform filters current

`windows` and `macos` list their tests in `PLATFORM_TESTS` (`.github/workflows/ci.yml`) as a
nextest filterset: `binary(=<name>)` for a `tests/<name>.rs` file gated as a whole
(`#![cfg(...)]`), `test(/(^|::)<name>$/)` for a single test. A new or renamed test gated to
Windows or macOS must be added there, or it runs nowhere. An empty selection passes
(`--no-tests=pass`). Tests gated to `unix`, `linux` or `any(macos, linux)` run on Linux and are
not listed.

## Known gaps

- Clippy and check run only for Linux targets, so code behind `cfg(windows)` and
  `cfg(target_os = "macos")` is compiled by the optional platform jobs but no longer linted in
  CI.
- `ketch-cs` and `ketch-win-app`: the .NET tests could run on Linux, but `scripts/csharp.sh`
  builds and places a Windows DLL; they stay on Windows until that script supports Linux.
- The `protect-main` ruleset change above is a repository setting and is not part of this PR.
