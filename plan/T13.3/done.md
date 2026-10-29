# T13.3. Done

The `ketch` command line builds and runs on Windows, and the desktop app's MSI carries it.

- Build: `mingwX64()` in `:ketch` and `:ketch-wire`; the POSIX-only pieces of `ketch/src` split
  into `unixMain` and `mingwMain`.
- Paths are joined with the host separator in ketch-core and the command line, through kit's
  `joinPath`, as Rust's `PathBuf::join` does, and compared by component.
- `src/`: the console (`isTerminal`, `terminalColumns`, virtual terminal, UTF-8 code pages,
  binary-mode stdout and stderr) and `system.rs`'s user PATH branches. The log is shared for
  deletion, so `self uninstall` can remove it while it is open. Detached children break away
  from kit's job.
- ketch-core: `resolvesInside` follows a link's target by Rust path component, prefix and root
  included, which only the SYSTEM run (the one that can create symlinks) reached.
- Tests: the e2e support's Windows side (`.cmd` plugin and programs, the `fakeTool` mingwX64
  program as the fake `mise`), `install_windows.rs` and `completion_windows.rs` in `mingwTest`,
  the Windows cases of the other e2e files; Unix-only tests in `unixTest`. `install_ps1.rs`
  stays in T15.
- MSI: `bundledKetch` copies the mingwX64 release `ketch.exe` into the app's `windows-x64`
  resources.
- kotlin-kit: the tests' `setEnv`/`unsetEnv` in `process`, `appendSink` shared for deletion, and
  the Conan build of the mingw libraries on a Windows host (K/N's Windows prebuilt, its clang,
  Ninja, forward-slash paths).

ketch-kotlin commits: `073989a` claim, `de80c66` ketch-core paths, `43acc56` test env, `8ec9079`
log, `75a8341` archive links, `53fced4` command line paths, `1b1a046` console and system,
`e2d278d` e2e suite, `0200569` MSI. kotlin-kit commits: `654a114` `joinPath`, `c8ece06`
`appendSink`, `cec6ab1` job breakaway, `9b557c6` `setEnv`/`unsetEnv`, `2d09ab8` Conan on a
Windows host.

Checked:

- `./gradlew build` in kotlin-kit and in ketch-kotlin on the Mac (compile, tests, ktlint,
  detekt). kit's `conanInstallMingwX64` still builds on the Mac after the Windows-host change.
- On the Parallels Windows 11 VM: ketch-core's mingwX64 tests, 726 of 726, as the user and as
  SYSTEM; `:ketch`'s, 259 of 259, as SYSTEM after the last ketch-core fix and as the user just
  before it (that fix changes only the symlink branch, which a user run cannot reach). Both
  PowerShells (5.1 and 7.6.9) ran in the completion test.
- `:desktop:packageMsi` on the VM (Temurin 21, Conan, CMake, Ninja): `Ketch-1.0.0.msi` built;
  its administrative extract holds `app\resources\ketch.exe`, and that `ketch.exe --version`
  prints `ketch 0.0.1 · preview` and `--help` works.

Not done: the intermediate commits were not built one by one, only the last one.

Repository note: during this task the ketch-kotlin repository lost five loose objects (`git diff`
failed on a missing blob). They were rebuilt from known content and `git fsck` is clean; the
cause is unknown. The rebuild left a few dangling trees, which are harmless.
