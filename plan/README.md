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
| T8 | `install`, `listing`, `stats`, `doctor`, `wizard`, `self_update` | ⬜ |
| T9 | `import` (brew, linux, winget, apply, fetch), `push` | ⬜ |
| T10 | `src/` command line: `cli`, `cmd/*`, `ui`, `complete`, `man`, `self_docs`; `tests/*.rs` end-to-end | ⬜ |
| T11 | `ketch serve` and `:ketch-wire` (the `ketch-ffi` surface), contract scenarios | ⬜ |
| T12 | Compose desktop app (the SwiftUI and WinUI screens) | ⬜ |
| T13 | Windows: mingwX64, `platform/windows`, Windows completions | ⬜ |
| T14 | `trust`: sigstore, minisign, OpenPGP | ⬜ |
| T15 | release: install scripts, self-update artefacts, CI, docs | ⬜ |
