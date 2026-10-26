# AGENTS.md — ketch-kotlin

If an `AGENTS.md` or `CLAUDE.md` exists higher in the tree, follow it too; on conflict, ask the creator.

## What this project is

A port of [ketch](https://github.com/pyrlyn/ketch) (Rust) to Kotlin: the core and the `ketch`
command line on Kotlin/Native, and one Compose Multiplatform desktop app (JVM) in place of the
SwiftUI and WinUI apps. ketch installs command-line tools and apps straight from GitHub releases,
checked against the published checksum, versioned, and cleanly removable.

The source of truth for behaviour is the Rust tree at `../ketch`. The port keeps the command line,
its output, the config and manifest TOML, `ketch.lock`, `state.json`, the `~/.ketch` layout and
`stats.db` interchangeable with Rust ketch.

## Where things are

| Path | What |
| --- | --- |
| `ketch-core/` | Port of `crates/ketch-core`; package `ketch.core.<rust module>` |
| `ketch/` | The `ketch` executable; port of `src/` (`cli.rs`, `cmd/`, `ui.rs`, …) |
| `ketch-wire/` | Records and JSON-lines protocol between `ketch serve` and the desktop app (port of `crates/ketch-ffi`'s surface); built for native and jvm |
| `desktop/` | Compose desktop app (JVM); runs the native `ketch` |
| `buildSrc/` | Convention plugins `ketch.kmp` and `ketch.compose` |
| `plan/` | Tasks, one folder per task; `plan/README.md` holds the port map and decisions |

## Build and test

Needs JDK 21, a checkout of `ci-diffuser` next to this one (`../ci-diffuser`, or
`CI_DIFFUSER_DIR`) and one of `kotlin-kit` (`../../packages/kotlin-kit`, or `KOTLIN_KIT_DIR`).
TOML, YAML, semver, HTTP and SQLite come from kotlin-kit: change them there, not here.

```bash
./gradlew build                    # everything: compile, tests, ktlint, detekt
./gradlew macosArm64Test           # native unit tests
./gradlew :desktop:run             # the desktop app
./gradlew ktlintFormat             # fix formatting
```

## Rules

- Code, docs, comments, commits and PR text in English.
- Port behaviour, not syntax. Every Rust unit and integration test of a ported file is ported
  to `kotlin.test`; a phase is done only when they pass on the Mac.
- Tests never touch the real `HOME`: the root build points `HOME` and `KETCH_HOME` at
  `build/test-home` for every native test run. No network in tests.
- Everything a release, an archive, a manifest or a registry contains is untrusted input; port
  every guard the Rust code has (zip-slip, size limits, checksum checks) together with its test.
- A third-party library sits behind our own interface, except Kotlin's own (stdlib, kotlinx.*,
  Ktor), so it can be replaced alone.
- ktlint and detekt must be clean.
- Commit in small green steps; every change ends in a PR once the repository has a remote.
