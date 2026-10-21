---
id: M11
title: Bash completion for every command
status: done
created_at: 1792583218
updated_at: 1792583218
---

Today clap_complete generates a static bash script (`ketch completions bash`, installed at `self install` as `share/ketch/completions/ketch`). It knows commands and flags, but not values.

Plan:
1. Test coverage: a test walks `Cli::command()` and asserts every visible subcommand, alias and flag appears in the generated script.
2. Dynamic values: installed package names for `uninstall`, `upgrade`, `pin`, `unpin`, `link`, `unlink`, `info`, `why`, `changelog` and `rollback`, read from the state file; registry names for `install` and `search`, read from the local registry copy. No network in completion. Use clap_complete `CompleteEnv` (`unstable-dynamic`) or a hidden `ketch __complete <kind>`; decide after checking how stable the feature is.
3. Docs: the bash ≥ 4 and bash-completion 2 note for macOS.

Check: bash smoke on Linux and macOS CI (`COMP_WORDS=(ketch un) COMP_CWORD=1` → `uninstall unlink unpin`; `ketch uninstall r<TAB>` → an installed name from a scratch root); `just check`.

Execution plan (as carried out):
1. Decision: a hidden `ketch __complete <installed|registry> [PREFIX]` command, not `CompleteEnv`. clap_complete 4.6's `unstable-dynamic` sits outside semver and its docs say the shell-to-binary protocol may change between releases, while ketch writes its bash script to disk at `self install`; a cargo patch update could break every installed script.
2. New `src/complete.rs`: the shell-agnostic candidate lists (state file, local registry copy, no network) and the bash script: clap_complete's static script plus a wrapper, generated from `Cli::command()`, that asks `ketch __complete` for positional package names. `main.rs` and `extra::write_ketch_docs` both call it.
3. Tests in `src/complete.rs`: every visible subcommand, alias and flag appears in the script; candidate filtering. `tests/`: bash smoke (`ketch un` → `uninstall unlink unpin`; `ketch uninstall r` → an installed fixture name) with `KETCH_ROOT` and `HOME` in a temp dir, skipped when no bash ≥ 4 is found.
4. Docs: `docs/COMMANDS.md` completions section, the bash ≥ 4 + bash-completion 2 note for macOS.

Result: `src/complete.rs` owns the completion scripts and `ketch __complete [--root DIR] <installed|registry> [PREFIX]`, intercepted in `main` before clap parses so no generated script, help or man page lists it. The bash script is clap_complete's plus `_ketch_packages`, registered in its place; the value-taking options it skips come from the clap tree, and names reach the command line only when they are plain (`[A-Za-z0-9._+@-]`), since registry folder names are someone else's input and `compgen -W` would expand them. Tests: unit tests in `src/complete.rs` (every visible subcommand, alias and flag in the script; candidate filtering; `self install` writes the same script); `tests/completion.rs` drives the script through the `bash` on PATH (3.2 on macOS) against a sandbox root. Docs: `docs/COMMANDS.md`.

Status: done 2026-09-30
Model: Claude Code / opus-5.5
