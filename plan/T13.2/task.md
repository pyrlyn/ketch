---
status: in progress
priority: P2
complexity: 4
readiness: 5%
agent: Claude Code / claude-opus-5-5
---

# T13.2. ketch-core on mingwX64

`platform/windows.rs` (the store, links and `.exe` shims, busy files moved aside, the user PATH
in the registry, PowerShell profiles and AutoRun), the Windows parts of `shell`, `process`,
`self_update`, `hooks`, `state`, `doctor` and `extract`, with every Rust test of them. Done when
`:ketch-core` builds for mingwX64 and its tests pass on the Windows VM.

## Plan

1. Build: declare `mingwX64` and a `unix` group (apple + linux) in `buildSrc`'s `ketch.kmp`; ketch-core
   takes kit `db-room` in `unixMain` and `db-native` in `mingwMain`, with an `expect` for the driver
   `Stats` opens. POSIX-only calls (`getpwuid`, `kill`) move to `unixMain` with a Windows `actual`
   (`USERPROFILE`, `TerminateProcess`).
2. Paths: `rustComponents`, `pathIsWithin` and the path keys read `\` and drive prefixes on Windows,
   and fold ASCII case where Rust does.
3. `platform/windows.rs` as `WindowsPlatform` in `mingwMain` (copies recorded as `CopiedFile`, busy
   files renamed aside and swept, case-insensitive destinations, `.exe` names, PowerShell completion
   dir, doctor), with `host()` returning it.
4. `shell.rs`: the Windows PATH helpers, PowerShell profile block, AutoRun edits and `doskey` file in
   `nativeMain` (tested on every host); the registry reads and writes through PowerShell, Documents,
   execution policy, `installUser`, `uninstallUser`, `installCmdMacros` in `mingwMain`.
5. `process.rs` (CIM process listing, `taskkill`-free terminate), `self_update.rs` (copies over a
   running `ketch.exe`, the profile block and AutoRun on self install), `hooks.rs` (`cmd /C`),
   `state.rs` (lock), `doctor.rs` (user PATH), `extract` and the others the inventory lists.
6. Tests: every Rust test of these files; the pure ones in `nativeTest`, the Win32 ones in `mingwTest`.

Check: `./gradlew build` on the Mac; `:ketch-core` mingwX64 `test.exe` passes on the Parallels
Windows VM as the user and as SYSTEM.
