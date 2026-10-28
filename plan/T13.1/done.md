# T13.1. Done

kotlin-kit declares `mingwX64` next to macOS arm64 and Linux x64/arm64. POSIX code moved to a
`unixMain` source set; each module's Windows side sits in `mingwMain` behind the same API.

- `cli`: console width and colour (GetConsoleScreenBufferInfo, virtual-terminal mode).
- `fs`: Win32 file system (CreateFileW, MoveFileExW, reparse points for links, file times).
  Every Win32 call goes through a C shim (`win.def`) that reads `GetLastError` inside C: from
  Kotlin the code was sometimes overwritten first (os error 0, about 1 run in 13). `canon` and
  `realPath` drop `\\?\` where it is safe, a port of dunce 1.0.5, as fs-kit's `dunce::canonicalize`
  does. A verbatim path gets backslashes, since kotlinx-io joins with `/`.
- `process`: CreateProcessW with the command line Rust std 1.99 builds (`resolve_exe` lookup
  order, `make_command_line`, the batch-file quoting that guards against BatBadBut), a job object
  to kill the whole tree (grandchildren included), pipes polled with PeekNamedPipe, exit codes
  printed as Rust's Display does (`exit code: 0xc0000005`). The pure command-line rules are tested
  on every host.
- `http-ktor`: Ktor's WinHttp engine.
- `archive`: Win32 file reads; bzip2 and liblzma built from ConanCenter's recipes with K/N's clang
  and mingw sysroot (ConanCenter has MSVC binaries only). The Conan logic is now one script,
  `gradle/conan.gradle.kts`, with the profiles in `conan/`, shared by `archive` and `db-native`.
- `loopback`: winsock beside POSIX sockets.
- SQLite: androidx.sqlite has no mingw build (2.7.1 and 2.8.0-alpha03 checked in Google Maven,
  2026-10-28), so `db-room` keeps macOS and Linux and its mingw klib is empty. New `db-native`
  opens a static sqlite3 3.54.0 (FTS5 on), built by Conan, through its own cinterop, with
  db-room's open flags. The creator chose this over SQLDelight's native driver: that driver pools
  connections and its cursor has no column names, and SQLiter, beneath it, sets `journal_mode` on
  every open. New `db-testing` holds the driver checks both adapters' tests run; db-sqldelight's
  tests pick db-room or db-native per target.

Checked: `./gradlew check` in kotlin-kit on the Mac; every module's tests compile for linuxX64
and linuxArm64. Every mingwX64 test binary ran on the Parallels Windows 11 VM, both as the user
and as SYSTEM (which has the symlink privilege): archive 41, cli 41, db-native 8, db-sqldelight 2,
fs 56, http-ktor 11, icons 8, loopback 2, process 25, semver 3, toml 32, toml-aot 12,
toml-ktoml 14, yaml-aot 6, all passing. ketch-kotlin's `./gradlew build` passes. rtok-kotlin
compiles; its `build` stops on two things outside the kit: a detekt MagicNumber in
`rtok-e2e/GuardCheckE2eSpec.kt:61` from its last commit, and a CLI test that sees this
session's `RTOK_AGENT_ID`.

Not done: the intermediate kit commits were not built one by one, only the last one. fs and
process each have their own FormatMessageW wrapper; they are independent modules and share no
code. The archive's mingw file reads do not go through an error-capturing shim yet, so a
failed open can name the wrong error code. ketch itself does not build for Windows yet (T13.2).

kotlin-kit commits: `07ad4a8` build, `7aa5f2f` cli, `3d59759` fs, `e027217` process, `8d81a38`
http-ktor, `b58b552` archive, `73669f3` loopback, `bb060dc` db-native, `3055d79` docs.
