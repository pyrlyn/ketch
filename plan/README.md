# ketch-kotlin

No GitHub repository yet (local only).

A port of ketch (Rust, `../ketch`, ~53k lines plus ~10k of tests) to Kotlin/Native for the core
and the command line, with one Compose Multiplatform desktop app.

## Decisions

Taken by the creator before the port started:

- **Desktop app ↔ core.** The core and the CLI are Kotlin/Native only. One Compose desktop app
  (JVM) replaces `desktop/macos` (SwiftUI) and `desktop/windows` (WinUI). It runs the native
  `ketch serve` and talks JSON lines with it: the calls, records, events, questions and
  cancellation of `crates/ketch-ffi`. The scenarios in `desktop/contract/scenarios` drive the
  app's fake core in its tests.
- **TUI.** `src/tui` (ratatui, an opt-in feature in Rust) is not ported; the line and progress
  renderer (`src/ui.rs`) is.
- **Targets.** macosArm64 first, linuxX64 and linuxArm64 through `diffuser.allTargets`; mingwX64
  with `platform/windows.rs` is a late phase of its own (T13).
- **Publisher signatures.** sigstore, minisign and OpenPGP verification come last (T14). Until
  then a manifest whose `trust` table requires a signature is refused with a clear error, as Rust
  ketch built without its `trust` feature does.
- **Shared libraries (T3).** TOML, YAML, semver, HTTP and SQLite come from the new included build
  `packages/kotlin-kit`, extracted from rtok-kotlin's `libs/` with neutral package names
  (`toml`, `tomlaot`, `yamlaot`, `semver`, `http`, `db`). rtok-kotlin moves onto it in a separate
  session.

Taken during the port:

- **SHA-256.** `org.kotlincrypto.hash:sha2` rather than rtok-kotlin's `Sha256`: that one hashes a
  whole byte array in memory, and ketch hashes downloads in chunks.
- **Files (T4).** Rust `fs-kit` is ported into kotlin-kit as `pyrlyn.kit:fs` (atomic, durable
  writes that keep modes and links; tree copy and removal) over POSIX. rtok-kotlin's `Fs.kt` was
  not reused: its writes are not atomic and it removes trees through `rm -rf`.
- **Writing TOML (T4).** kotlin-kit's `toml.TomlWriter` lays a file out exactly as Rust `toml`
  1.1 `to_string_pretty` does (string forms, multi-line arrays, implicit tables), checked against
  that crate's own output, so `ketch.lock` and `config.toml` are byte-identical across the two.
  No library encoder is used for writing. Values are mapped through kotlinx-serialization JSON
  trees: a null is an absent key, a default is written out.
- **Reading TOML (T4).** kotlin-kit's own `toml.TomlParser`, a strict TOML 1.1 reader (the spec
  Rust `toml` 1.1 reads) that passes every TOML 1.1.0 file of toml-test, with Rust's nesting bound
  of 80. ktoml 0.7.1 refuses inline tables inside a multi-line array (manifests' `extra_paths`);
  tomlkt 0.6.1 refuses quotes right before a closing `'''`/`"""`, which Rust writes, and accepts
  70 of toml-test's invalid files. A datetime reads as the text Rust's Display gives it.
- **Archives (T5).** The tar and zip readers and writers are kotlin-kit's own `pyrlyn.kit:archive`
  (package `archive`); ketch-core keeps every guard (member paths, links, budgets, modes). gzip
  and deflate use zlib through K/N's `platform.zlib`. bzip2 1.0.8 and liblzma from xz_utils 5.8.4
  are the C libraries, linked statically from ConanCenter prebuilt binaries pinned by
  `archive/conan.lock`; Conan is installed through mise. No maintained Kotlin/Native library
  reads tar, zip, bzip2 or xz. Differences from Rust, all on the side of refusing more: the xz
  memory limit is counted in bytes; tar names that are not UTF-8 decode lossily; the kit caps GNU
  long names at 64 KiB, PAX headers at 1 MiB and sparse maps at 100k runs, checks the zip entry
  count against the central directory's size and ignores a stale Unicode path field. A zip that
  fails while a member is read is a parse error. `hdiutil`, `pkgutil` and `ditto` run under a
  60-minute deadline (Rust has none); a missed deadline is a `KetchError.Command`.
- **Platform, processes, shell (T6).** `walkdir` and `tempfile` are kotlin-kit's `fs.walk` and
  `fs.TempDir`. `LinuxPlatform` sits in nativeMain, so its placement is tested on the Mac too;
  `host()` picks the backend per target. Stopping a process is `kill(2)` (SIGTERM, then SIGKILL
  after 200 ms) rather than the `kill` command, and `codesign`, `spctl`, `xattr` and `lsof` run
  under the bounded runner of `pyrlyn.kit:process`, which now names the child a timeout stopped.
  Discovered executables are ordered by path components, as Rust compares `Path`s. Windows code,
  and the Windows-only helpers Rust compiles everywhere as dead code (`windows_path_*`, PowerShell
  profile writing, AutoRun, the user PATH in the registry), came with T13.2; on Unix those entry
  points answer "nothing here", as Rust's do.
- **Sources and resolution (T7).** HTTP goes through kotlin-kit's `http` client, which now
  streams a GET body as it arrives, so ketch caps a body while it downloads and hashes it on the
  way; GitHub, local and plugin sources are tested against recorded responses and kotlin-kit's
  `pyrlyn.kit:loopback`, a hand-written HTTP/1.1 server on 127.0.0.1 that can break its own
  framing, in place of Rust's `TcpListener` tests. No test touches the network. Plugins run
  through the bounded runner of `pyrlyn.kit:process` (30 s, 8 MiB of output). `ketch update`
  stages the registry in `fs.TempDir.createIn(root)`, so the final rename stays on one
  filesystem as Rust's `tempdir_in` does. The file errors of kit calls go through one shared
  `fsKitAt`. Rust's registry `CheckReport` keeps its name; the source table is `SourceRegistry`
  in `SourceRegistry.kt`, since detekt wants a file named after its one class.
- **Batch installs (T8).** `install.batch` installs one package at a time whatever `--jobs`
  says, where Rust prepares up to `jobs` side by side. kotlinx-io 0.9.1 on Apple implements
  `Path.name` and `Path.parent` with `basename(3)` and `dirname(3)`, which return a shared static
  buffer (`core/apple/src/files/FileSystemApple.kt` on its `master`, checked 2026-10-27): two
  threads preparing packages read each other's file names (`aravo` for `alpha` and `bravo`).
  The order of results, per-package failures and the commit sequence stay Rust's, and with
  `jobs` above one every request is still prepared against the state from before the batch
  (T10), so a package asked for twice installs cleanly as it does in Rust. For the same
  reason `ketch list` asks each source for its `latest` one after another, not `jobs` at a time.
- **Statistics (T8).** `stats.db` goes through SQLDelight 2.4.1 (the creator's choice; Rust uses
  diesel) on kotlin-kit's `pyrlyn.kit:db-sqldelight`, a `SqlDriver` over kit `db`, so SQLite stays
  the bundled one. The schema is Rust's migration with its declared column types (`INTEGER`,
  `TEXT`) and `IF NOT EXISTS`; a new database also gets diesel's `__diesel_schema_migrations`
  row, so either ketch opens a `stats.db` the other wrote.
- **Self commands (T8).** `self install`, `self upgrade` and `self uninstall` run Homebrew and mise
  on the shared terminal through kit `process.Command.status`; the running binary comes from kit
  `currentExe`, and the swap renames through kit `fs.rename` so a transient lock keeps its errno.
  The Windows parts (PowerShell and cmd completion, registry entries, moving a running `.exe`
  out of the way, removal after exit) and their two probe tests came with T13.2. Publisher
  signatures on a self upgrade stay stubbed until T14, like every other install.
- **Import and push (T9).** `import` is a Kotlin keyword, so the package is `ketch.core.importer`.
  winget manifests are read with kit `yaml-aot` and handed to kotlinx.serialization as JSON in
  which every scalar is its source text, so `PackageVersion: 2.0` stays "2.0" as serde-saphyr
  keeps it. `ketch registry push` speaks the GitHub REST API through the project's `Http`
  (`getJsonOpt` for reads where 404 is an answer, `sendJson` for POST, PUT and PATCH, the last
  two new in kit `http`); octocrab has no Kotlin counterpart. Rust's test that octocrab can be
  built without a running tokio runtime becomes a check that the client is built from plain
  synchronous code. Rust's `push::File` is `RemoteFile`, which says where the file lives.
- **Desktop app (T12).** The creator's choices on claiming it: five subtasks (T12.1–T12.5);
  Material 3 on the brand tokens of `desktop/design/tokens.json` rather than an imitation of
  Liquid Glass; no self-updates (Sparkle has no counterpart for a jpackage app); the native
  `ketch` ships inside the app's package, and `KETCH_EXE` overrides it.
- **macOS look (T16).** The creator's choices: on macOS the app wears the Liquid Glass design of
  the `ketch Store — Discover` canvas, which replaces T12's Material 3 there. Its components live in the shared `kotlin-glass` package and hold no business
  logic: data, token mapping and screens stay in `desktop/src/main/kotlin/ketch/desktop/ui/glass/`.
- **Windows look (T17).** On Windows the app wears the `ketch Store — Windows 11` canvas in the
  colours of the `Pyrlyn Windows 11` design system, in place of T12's Material 3; high contrast keeps
  Material 3, which the design system has no answer for, and Linux kept it until T20. The components live
  in the shared `kotlin-fluent` package and hold no business logic; compose-fluent-ui was passed over
  because its controls carry Microsoft's stock tokens and it sits on Compose 1.8. Logic both looks
  share is in `ui/Host.kt`, `ui/Log.kt` and `ui/Summaries.kt`. `KETCH_LOOK` names a look to see it on
  another system.
- **Windows window (T18).** The window stays Compose's undecorated one and draws its own title bar;
  `WindowsFrame` in `kotlin-fluent` adds the system's corners, shadow, menu and Mica or Acrylic, over
  JNA, which is `internal` there. The title bar reports `WindowZones`, and Windows treats the
  maximize button as its own, which is what offers Snap Layouts; Compose then sees neither hover nor
  click on the caption buttons, so both come back through `WindowZones`. `attach` fails open: off
  Windows 11 or on any failure the window keeps the frame T17 gave it. The wall drops out only for a
  backdrop that was really applied, on the graphics API Skiko actually got. The Parallels VM has no
  Direct3D 12, so it runs ANGLE; real hardware runs DX12.
- **Windows notice (T19).** The Update button needs a live process to hear the click, and a packaged
  app's COM activator needs an identity an MSI lacks. So a hidden PowerShell child posts the notice
  and waits for it, the AppUserModelID goes under `HKCU`, and the tray's balloon is the fallback.
- **Windows (T13).** The creator's choices on claiming it: three subtasks (T13.1–T13.3);
  mingwX64 and the Windows implementations go into kotlin-kit itself; tests run on a Windows
  VM in Parallels on the Mac (Windows on Arm, which runs the x64 binaries under emulation). No
  subtask closes without that run.
  SQLite on Windows is kit `db-native` (the creator's choice after androidx.sqlite turned out
  to have no mingw build): SQLite's C API over a static sqlite3 Conan builds with K/N's mingw
  toolchain; ketch picks `RoomDbDriver` on macOS and Linux, `NativeDbDriver` on Windows.
- **ketch-core on Windows (T13.2).** Source sets: `unixMain`/`unixTest` (Apple and Linux) and
  `mingwMain`/`mingwTest`; a Rust `#[cfg(unix)]` test goes to `unixTest`, a `#[cfg(windows)]` one
  to `mingwTest`. kotlinx-io joins paths with `/` on Windows, so ketch's paths come out mixed
  (`C:\Users\u\.ketch/bin`): Win32 takes both, the path keys and `pathIsWithin` read both, and
  `nativeSpelling` writes backslashes wherever a path is shown to Windows itself (the user PATH,
  AutoRun, PowerShell, a command line another process is matched by). How paths are displayed
  is left to T13.3, with the CLI. The home directory is `USERPROFILE`. The registry is read and
  written through Windows PowerShell, as in Rust, with a 120 s guard Rust does not have (a hung
  PowerShell must not hang `ketch path install`). kit gained `Command.rawArg` (hooks
  run through `cmd /C` exactly as Rust's `raw_arg` does), `Command.spawnDetached` (removing the
  root after `self uninstall` exits) and `systemTempDir`, and its Windows `rename` now retries
  with the POSIX rename Rust's std uses when MoveFileExW refuses a file another process has open.
  The lock test's crashed contender writes its stale pid in place, as Rust's `fs::write` does:
  replacing the file needs every other handle on it to allow deletion, and on Windows one often
  does not for about a second. Rust's `sibling_keeps_a_non_utf8_file_name` is ported for
  the names a Kotlin `Path` can hold: a `String` carries no undecodable bytes, but it does carry
  an unpaired surrogate, a Windows name that is not valid Unicode. kotlinx-io's `Path.parent` and
  `Path.name` go through C `dirname`/`basename` as UTF-8 and turn one into U+FFFD, so kit's
  `exactParent`/`exactName` cut the answer from the original text; `createDirAll`, the atomic
  write's staging dir and the store swap use them. The other `parent`/`name` calls still use
  kotlinx-io's. `splitPathVar` reads a Windows PATH as Rust's `split_paths` does: `"` quotes a `;`
  into an entry and is dropped (the registry edits keep their own split, which leaves quotes in
  place because they write the value back).
- **The CLI on Windows (T13.3).** Paths are joined with the host separator everywhere, through
  kit's `joinPath`, so what ketch prints matches Rust's `PathBuf::join` (`C:\Users\u\.ketch\bin`);
  a one-argument `Path("a/b")` keeps what it was given, as Rust's `PathBuf::from` does.
  kotlinx-io's `Path` drops a trailing separator, so a prefix to strip is built as text with
  kit's `PATH_SEPARATOR`. Paths are compared by component (`pathIsWithin`), never as strings.
  A `local:` file installs under a name made from its whole path on Windows, because Rust's
  `short_name` splits only on `/`; that is kept, and the tests use the name the install
  reports, as Rust's do. The console layer is `isTerminal` (`GetConsoleMode`, since `_isatty`
  also says yes to `NUL`), `terminalColumns` (`GetConsoleScreenBufferInfo`) and the
  virtual-terminal switch Rust gets from `console`; `main` sets the console's code pages to
  UTF-8. The test-only `setEnv`/`unsetEnv` moved into kit `process`. `install_ps1.rs` stays
  with the installers (T15). The VM has PowerShell 7.6.9 and Temurin 21.0.12 from their
  official MSIs, so the completion test drives both PowerShells, as Rust's does.
  stdout and stderr are put in binary mode, as Rust writes bytes: the CRT's text mode would turn
  every `\n` into `\r\n`. The log is opened through kit's `appendSink`, shared for deletion as
  Rust's file is, because `self uninstall` removes the log directory while the log is still open.
  kit's piped children run in a job that ends them with the test, so the job allows breakaway
  and a detached child (the uninstall's cleanup PowerShell) leaves it. `resolvesInside` reads
  paths by Rust component (prefix, root, both separators). Only SYSTEM can create the symlinks
  that check is for, so the suite runs as SYSTEM too.
  Windows runs only an `.exe` it finds on PATH, so the tests' fake `mise` is a small mingwX64
  program of the `ketch` module (`fakeTool`), as Rust compiles its fakes with rustc. Windows
  PowerShell's default policy refuses scripts, so the completion test lifts it with
  `-ExecutionPolicy Bypass` for its own process only; the machine's policy is never changed.
  Every ketch call lists processes through `Get-CimInstance`, as Rust's does, which is most of
  what makes the Windows suite slow.
  The MSI carries the mingwX64 release `ketch.exe` in the app's `windows-x64` resources and is
  built on a Windows host, since jpackage packages only for the host it runs on. That host
  builds kit's Conan libraries itself: Conan 2.33.0, CMake 3.31.12 and Ninja 1.13.2 sit in
  `C:\Users\Public\tools` on the VM, on the user's PATH, and kit's `conan.gradle.kts` reads
  K/N's Windows prebuilt and drives its clang with Ninja there.
- **`ketch serve` (T11).** One JSON object per line each way, `:ketch-wire`'s records with the
  Rust field names: `hello` first, then `event`, `ask` and `result` per call id; the app sends
  `call`, `answer` and `cancel`. `serve` is read before the parser, as `__complete` is, so the
  help, man pages and completions stay Rust's. Input is read on its own coroutine, so answers
  and cancels reach a running call; calls run one at a time in arrival order. A cancel declines
  the call's open questions and ends a queued call at once; the end of input declines open
  questions and lets what was sent finish. A line that is no message, or a call id still in
  use, gets `rejected`; an answer or cancel for a call that ended is dropped. `KetchCore`
  (`lib.rs`) passes the call's cancel into `Ctx` too, because the Kotlin core checks it in
  lookups and the registry download.
- **Linux look (T20).** On Linux the app wears the `Ketch Linux` design, dark only, in place of
  T12's Material 3; high contrast keeps Material 3, and `KETCH_LOOK=adwaita` shows it elsewhere. The
  components live in the shared `kotlin-adwaita` package and hold no business logic; no ready Compose
  Adwaita library exists. Sections sit in a view switcher and the hamburger menu instead of a
  sidebar, and search in a bar under the header. An undecorated AWT window has no resize edges on
  Linux, so `Edge.kt` draws them and reads the pull from `MouseInfo`; rounded corners need a see-through
  window, so `desktopShowsThrough` falls back to square ones where the desktop composites nothing.
  The design's controls the core has no call for stay out rather than sit idle: see `plan/T20/done.md`.

## Goals of the port

- The command line, its flags and its output stay those of Rust ketch.
- On-disk formats stay interchangeable: `config.toml`, manifests, `ketch.lock`, `state.json`,
  the `~/.ketch` store layout and `stats.db`.
- Every Rust test of a ported file is ported to `kotlin.test` and passes on the Mac.

## Not ported

| Rust | Why |
| --- | --- |
| `src/tui` | Creator's choice (see Decisions) |
| `crates/ketch-capi`, `crates/ketch-ffi` (UniFFI) | The desktop app runs `ketch serve` instead of linking the core |
| `desktop/macos`, `desktop/windows` | Replaced by `desktop/` (Compose) |
| Sparkle self-updates of the macOS app | Creator's choice (see Decisions); see `ideas.md` |
| `fuzz/` | No cargo-fuzz on Kotlin/Native; see `ideas.md` |
| `workspace-hack`, `site/` | Rust build plumbing and the docs website |

## Structure

| Rust | Gradle module | Kotlin package |
| --- | --- | --- |
| `crates/ketch-core` | `:ketch-core` | `ketch.core.<module>` (one package per Rust module) |
| `src/` | `:ketch` | `ketch.cli` |
| `crates/ketch-ffi` surface | `:ketch-wire` (native + jvm) | `ketch.wire` |
| `desktop/` | `:desktop` (JVM, Compose) | `ketch.desktop` |

## Port map

The Rust modules each task covers. ✅ ported with tests green, 🟡 partial, ⬜ not started.

| Task | Rust | Status |
| --- | --- | --- |
| T1 | build scaffolding | ✅ |
| T2 | `error`, `text`, `diff`, `cancel`, `decide`, `bin_choice`, `platform/scoring` | ✅ |
| T3 | shared Kotlin libraries: TOML (read + comment-keeping edit), YAML, semver, SHA-256, HTTP, SQLite | ✅ |
| T4 | `config`, `toml_file`, `log`, `report`, `model`, `manifest`, `extra`, `hooks`, `lockfile`, `state`, `fsio`, `link` | ✅ |
| T5 | `extract` (tar, gzip, xz, bzip2, zip, macOS dmg/pkg) | ✅ |
| T6 | `platform` (mod, unix, macos, linux, claim), `process`, `shell` | ✅ |
| T7 | `http`, `source` (github, local, plugin), `resolve`, `releases`, `changelog`, `info`, `registry` | ✅ |
| T8 | `install`, `listing`, `stats`, `doctor`, `wizard`, `self_update` | ✅ |
| T9 | `import` (brew, linux, winget, apply, fetch), `push` | ✅ |
| T10 | `src/` command line: `cli`, `cmd/*`, `ui`, `complete`, `man`, `self_docs`; `tests/*.rs` end-to-end | ✅ |
| T11 | `ketch serve` and `:ketch-wire` (the `ketch-ffi` surface), contract scenarios | ✅ |
| T12 | Compose desktop app (the SwiftUI and WinUI screens) | ✅ |
| T13 | Windows: mingwX64, `platform/windows`, Windows completions | ⬜ |
| T14 | `trust`: sigstore, minisign, OpenPGP | ⬜ |
| T15 | release: install scripts, self-update artefacts, CI, docs | ⬜ |
