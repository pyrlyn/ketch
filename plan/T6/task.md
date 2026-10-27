---
status: in progress
priority: P0
complexity: 4
readiness: 0%
agent: Claude Code / claude-opus-5-5
---

# T6. Platform backends, processes, shell

`platform` (`mod`, `unix`, `macos`, `linux`, `claim`), `process` (busy files, stopping
processes), `shell` (PATH setup, completions install). POSIX through cinterop; macOS specifics
(quarantine, `/Applications`, codesign checks) as in Rust.

## Plan

About 6.1k lines of Rust and 100 tests on macOS and Linux, ported from 41738f64. Scope: what Rust
compiles on macOS and Linux. `#[cfg(windows)]` code, and the Windows-only helpers Rust compiles
everywhere as dead code (`windows_path_*`, PowerShell profiles, the cmd AutoRun and doskey
macros), go to T13 with their tests.

1. `platform` (nativeMain): `Placement`, `TrustVerdict`, `DoctorCheck`, `Platform`, `dataHome`,
   `configHome`, `pathIsWithin`, `completionDirFor`, `extraDestinationChecks`, the sidecar and
   rejection tables, `orderDiscoveredExecutables`, `isBusyAsideOf`, `sibling`, the store swap,
   `exposeExtras`, `host()`; `claim` (`Claims`, `asideName`).
2. `platform/unix`: discovery, bin specs, link and unlink, preflight, extras, ownership checks.
3. `platform/macos` (macosMain) and `platform/linux` (linuxMain): placement, `.app` bundles,
   quarantine and `codesign`/`spctl` trust, doctor checks.
4. `process`: who has a file open (`lsof` on macOS, `/proc` on Linux), offer to stop, terminate.
5. `shell`: rc-file blocks for bash, zsh and fish, detection, status, doctor PATH check.

Files: `ketch-core/src/nativeMain/kotlin/ketch/core/{platform,process,shell}/`,
`macosMain`/`linuxMain` for the backends, tests beside them. Check: `./gradlew build`, the
linuxArm64 compile with `-Pdiffuser.allTargets=true`, every ported test green on the Mac.

