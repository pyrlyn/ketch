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
  profile writing, AutoRun, the user PATH in the registry), wait for T13 with their tests; on
  Unix those entry points answer "nothing here", as Rust's do.
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
  out of the way, removal after exit) and their two probe tests wait for T13. Publisher
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
- **Windows (T13).** The creator's choices on claiming it: three subtasks (T13.1–T13.3);
  mingwX64 and the Windows implementations go into kotlin-kit itself; tests run on a Windows
  VM in Parallels on the Mac (Windows on Arm, which runs the x64 binaries under emulation). No
  subtask closes without that run.
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
