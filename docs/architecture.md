# Architecture

ketch-kotlin ports [ketch](https://github.com/pyrlyn/ketch) (Rust). The Rust tree is the source of
truth for behaviour: the command line, its output, the config and manifest TOML, `ketch.lock`,
`state.json`, the `~/.ketch` layout and `stats.db` stay interchangeable with Rust ketch.

| Module | What |
| --- | --- |
| `ketch-core/` | Port of `crates/ketch-core`; package `ketch.core.<rust module>`. Models, sources, manifests, install, state, reporting. |
| `ketch/` | The `ketch` executable (Kotlin/Native); port of `src/` (`cli.rs`, `cmd/`, `ui.rs`, …). The command tree is declared once in `ketch/cli/`. |
| `ketch-wire/` | Records and the JSON-lines protocol between `ketch serve` and the desktop app; built for native and JVM. |
| `desktop/` | Compose desktop app (JVM) that runs the native `ketch`. Glass on macOS (`ui/glass/`), Fluent on Windows (`ui/fluent/`), Adwaita on Linux (`ui/adwaita/`), Material 3 elsewhere (`ui/screens/`). `KETCH_LOOK=fluent` shows another system's look. |
| `buildSrc/` | Convention plugins `ketch.kmp` and `ketch.compose`. |
| `plan/` | Tasks, one folder per task; `plan/README.md` holds the port map and decisions. |

## How the core reports progress

The core prints nothing. It sends `ketch.core.report.Event`s to a `Reporter`: the CLI renders them
as lines and bars, the desktop app (through `ketch serve` and `ketch-wire`) as its own views, and
tests record them.

## Shared libraries

Files, TOML, YAML, semver, HTTP and SQLite come from `kotlin-kit` in the workspace's `packages/`;
the desktop looks come from `kotlin-glass`, `kotlin-fluent` and `kotlin-adwaita`, which hold no
business logic. See [building.md](building.md).
