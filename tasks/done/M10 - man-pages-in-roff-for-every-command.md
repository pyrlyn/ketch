---
id: M10
title: Man pages in roff for every command
status: done
created_at: 1792583218
updated_at: 1792583218
---

Today `extra::render_manpage` writes one hand-rolled `ketch.1`, listing top-level commands with no options and no nested subcommands. `write_ketch_docs` places it under `share/man/man1/` at `self install`.

Plan:
1. Add `clap_mangen` (rtok already uses it for `rtok man`, a working reference). Generate `ketch.1` plus `ketch-<cmd>.1` for every visible subcommand, recursively (`ketch-config-create.1`, `ketch-self-uninstall.1`, …), with options, defaults, env vars and examples from the clap definitions. Replace `render_manpage`.
2. Write every page through `write_ketch_docs` as `ExtraPath` records, so uninstall and relink remove them with the same ownership proof as today's page.
3. A hidden `ketch man --out <dir>` (or a `just man` recipe) for packaging; the Homebrew cask may ship them.

Check: a test walks `Cli::command()` and asserts one page per visible command; `mandoc -Tlint` clean on macOS and Linux CI; `man ketch-install` works after `self install` in a scratch root; `just check`.

Execution: `src/man.rs` renders every page with `clap_mangen` 0.3 (`env` feature) from `Cli::command()`: `ketch.1`, then `ketch-<cmd>[-<sub>…].1` depth first for each subcommand that is not hidden and not clap's generated `help`. Titles are upper case, the source is `ketch <version>`, the date is `SOURCE_DATE_EPOCH` when set (reproducible packaging) or today. Two clap_mangen layouts that `mandoc -Tlint` warns about (a break beside a blank line before "Possible values") are tidied into one `.sp`. `extra::write_ketch_docs` writes every page under `share/man/man1/` of the store prefix and records each as an `ExtraPath`; `extra::render_manpage` is gone. A hidden `ketch man --out <DIR>` writes the same pages without a ketch root, for packaging. `just lint-man` (part of `just check`) runs `mandoc -Tlint -Wwarning` on them when mandoc is present and says it skipped otherwise; the Linux CI job does not install mandoc.

Tests: `src/man.rs` walks `Cli::command()` and asserts one page per visible command in order, nested pages exist (`ketch-self-uninstall.1`), hidden commands and `help` get none, a subcommand page names its full invocation and options, the tidy step, and `write_to`. `src/extra.rs` asserts every page is written into the prefix and recorded as a man extra. Verified by hand: `mandoc -Tlint -Wwarning` clean on all 42 pages on macOS; `self install` in a scratch root (KETCH_ROOT, HOME and XDG_* under `target/`, a local mock of the GitHub API serving the built binary) linked 42 pages into `$XDG_DATA_HOME/man/man1`, `man ketch-install` rendered, and `self uninstall` removed them all.
