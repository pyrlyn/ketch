# T13.2. Done

ketch-core builds for mingwX64 next to macOS arm64 and Linux x64/arm64, with `unixMain` and
`mingwMain` behind one API.

- Build: `mingwX64` and a `unix` group in `ketch.kmp`; SQLite through kit `db-native` on Windows
  and `RoomDbDriver` elsewhere, picked by an `expect` driver; `getpwuid`, `kill` and the lock's
  liveness check have Windows actuals (`USERPROFILE`, `taskkill`, `tasklist`).
- Paths: `rustComponents`, `pathIsWithin`, `pathLookupKey` and the process path key read `\`,
  drive prefixes and fold ASCII case where Rust does.
- `platform/windows.rs` as `WindowsPlatform`: copies recorded as `CopiedFile`, a running `.exe`
  renamed aside and swept later, case-insensitive destinations, `.exe` names, the PowerShell
  completion dir under Documents.
- `shell.rs`: the user PATH in the registry (raw `REG_EXPAND_SZ`, `%VAR%` kept), PowerShell
  profile blocks for both editions, cmd's AutoRun and the `doskey` macro file.
- `process.rs`: the CIM process listing and `taskkill` stop. `hooks.rs`: `cmd /C` with the
  command as a raw argument. `extract`: a symlink falls back to a same-tree copy without the
  privilege. `self_update.rs`: bootstrap copy, PowerShell and cmd completion on self install,
  registry entries removed on uninstall, a detached PowerShell that removes the root once ketch
  has exited, the running `.exe` moved out of mise's tree, mise under LocalAppData.
  `doctor.rs`: `--fix` puts the bin dir on the user PATH.
- Tests: every Rust test of these files. Rust's `#[cfg(unix)]` tests moved to `unixTest`,
  its `#[cfg(windows)]` ones are in `mingwTest` (probe, process listing and timeouts, a running
  `.exe` cleared for replacement, device names, the real registry).
- kotlin-kit: `Command.rawArg`, `Command.spawnDetached`, `systemTempDir`, and a Windows `rename`
  that retries with the POSIX rename, as Rust's std does, when another process has the target
  open.

Checked: `./gradlew build` in kotlin-kit and in ketch-kotlin on the Mac. The ketch-core mingwX64
test binary ran on the Parallels Windows 11 VM: 723 of 723 pass as the user and as SYSTEM. The
kit's fs (58) and process (28) Windows test binaries pass there as the user and as SYSTEM.

Not done: the intermediate commits were not built one by one, only the last one. Paths are shown
with mixed separators until T13.3 settles display. Rust's non-UTF-8 sibling test is not
portable (a Kotlin `Path` is a `String`).

ketch-kotlin commits: `61d555d` build, `d183ed3` platform, `09ab4fa` process, `9ee20d0` shell,
`7ce3d6c` hooks, `be5c425` links and sources, `f48c77a` self-update and doctor. kotlin-kit
commits: `b768a79` raw arguments, `99d364a` detached spawn, `13a1f5a` rename, `eb831c2` temp dir.
