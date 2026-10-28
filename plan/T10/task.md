---
status: in progress
priority: P0
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T10. Command line

`src/`: `cli`, `cmd/*`, `ui` (lines, icons, progress bars), `complete`, `man`, `self_docs`, and
the end-to-end suite of `tests/*.rs` against the built binary. Help text and output match Rust
ketch.

## Plan

About 7.4k lines of Rust in `src/` (without `tui`) and 12k lines of tests, ported from 41738f64.

1. kotlin-kit `:cli`, a module of its own (the creator's choice over Clikt behind an adapter):
   a declarative command tree with clap's semantics (global, short and long options, visible
   and hidden aliases, hidden commands, `required`, `required_unless_present`,
   `conflicts_with`, `requires`, value enums, defaults, ranged numbers, flattened groups), a
   parser, and clap 4.6's help and error output byte for byte (short and long help, wrapping at
   the terminal's width, else `COLUMNS`, else 100 columns, `[alias: i]`, `[default: 20]`, `tip:` lines, exit 2).
   Completion scripts and roff man pages are rendered from the same tree. Clikt leaves `:ketch`.
2. `ui`: status lines, icons, colour (`NO_COLOR`, `--no-color`, a terminal or not), progress
   bars and prompts, with `ui.rs`'s unit tests and its three snapshots.
3. `cli` (the tree), `main` (the error and exit-code funnel), `self_docs`, `man` and `complete`
   (the `__complete` callback).
4. `cmd/*`: `pkg`, `query`, `system`, `config`, `registry`, `lock`, `import`.
5. End to end: `tests/support` (a scratch `HOME`, recorded releases served on loopback through
   kit's `:loopback`, no network) and every `tests/*.rs` against the built `ketch.kexe`,
   including the `tests/cases/*.trycmd` snapshots.

Out of scope: the `tui` feature (on the roadmap as its own task) and the shell-script tests
(`tests/*.sh`, release tooling: T15).

Of `tests/*.rs`, these stay with other tasks or are not ported:

- `trust.rs`: T14, with signature verification. Until then the port refuses a manifest that
  requires a signature.
- `install_sh.rs`, `install_ps1.rs`, `release_changelog.rs`, `site_version.rs`: the installer
  scripts and release tooling, T15.
- `completion_windows.rs`, `install_windows.rs`: Windows only, which the port does not target.
- `cli.rs`'s `unsupported_host` module and the `tui`-only test: hosts and a feature the port
  does not build.
- `workspace.rs`: only "the core reports the version the binary was built as"; the rest checks
  Cargo metadata.

`install.rs` is macOS only and lives in `ketch/src/macosTest/`; `install_linux.rs` lives in
`ketch/src/linuxTest/` and compiles with `-Pdiffuser.allTargets=true`.

Files: kit `cli/`; `ketch/src/nativeMain/kotlin/ketch/cli/`, tests in `ketch/src/nativeTest/`.
Check: `./gradlew build`, the linuxArm64 compile with `-Pdiffuser.allTargets=true`, kit `check`,
every ported test green on the Mac.
