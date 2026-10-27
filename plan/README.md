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
  ktoml's encoder is not used for writing. Values are mapped through kotlinx-serialization JSON
  trees: a null is an absent key, a default is written out.

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
| T4 | `config`, `toml_file`, `log`, `report`, `model`, `manifest`, `extra`, `hooks`, `lockfile`, `state`, `fsio`, `link` | ⬜ |
| T5 | `extract` (tar, gzip, xz, bzip2, zip, macOS dmg/pkg) | ⬜ |
| T6 | `platform` (mod, unix, macos, linux, claim), `process`, `shell` | ⬜ |
| T7 | `http`, `source` (github, local, plugin), `resolve`, `releases`, `changelog`, `info`, `registry` | ⬜ |
| T8 | `install`, `listing`, `stats`, `doctor`, `wizard`, `self_update` | ⬜ |
| T9 | `import` (brew, linux, winget, apply, fetch), `push` | ⬜ |
| T10 | `src/` command line: `cli`, `cmd/*`, `ui`, `complete`, `man`, `self_docs`; `tests/*.rs` end-to-end | ⬜ |
| T11 | `ketch serve` and `:ketch-wire` (the `ketch-ffi` surface), contract scenarios | ⬜ |
| T12 | Compose desktop app (the SwiftUI and WinUI screens) | ⬜ |
| T13 | Windows: mingwX64, `platform/windows`, Windows completions | ⬜ |
| T14 | `trust`: sigstore, minisign, OpenPGP | ⬜ |
| T15 | release: install scripts, self-update artefacts, CI, docs | ⬜ |
