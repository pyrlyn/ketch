---
id: B69
title: "Uninstalling a package that is not installed prints only \"not found\""
status: done
created_at: 1792583854
updated_at: 1792583854
---

Ivan: uninstalling an already-removed program prints only "program not found".

Today `cmd::pkg::uninstall` resolves every name first and fails with `Error::NotInstalled` (`` `<name>` is not installed ``, exit 4), rendered by `ui::error` with the `error` label.

Plan:
1. A missing name prints one line, `<name>: not found`, with no hint, no detail lines and no summary. Exit code 4 stays for scripts. Wording: ketch's docs say "package"; Ivan's phrase was "program not found". Confirm the final text with Ivan.
2. Several names: every missing one is reported, and nothing is removed (the up-front resolution stays, so a typo still stops the command).
3. After B67: a name with no state record but a leftover `store/<name>/` gets the leftover removed and still reports not found.

Check: e2e: uninstall twice → the second run prints exactly one line and exits 4; trycmd snapshot; `just check`.

Done (Claude Code / opus-5.5): wording confirmed by Ivan as `<name>: not found`. `cmd::pkg::uninstall` collects every name state cannot find, prints one `ui::bare_error` line per name (no label, hint or detail, logged at error level) and returns `Error::Reported(4)`, which `main` exits with without printing anything else — not even the log-path note. No installed package is removed when any name is missing. A missing name's leftover `store/<name>/` is removed through B67's `install::remove_package_dir`. Tests: `tests/uninstall_not_found.rs` (one name, several names, a leftover folder; all OSes).

Status: done 2026-09-30
