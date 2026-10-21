---
id: F9
title: "`ketch install <pkg>` on an installed package offers the update"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Ivan: `ketch install <program>` when it is already installed asks "update?". Yes updates. With no update available, it says it cannot install because the package is already installed.

Today `install::prepare` returns `Error::AlreadyInstalled` (exit 5, hint "Use --force to reinstall.") only when the resolved tag equals the installed one. When a newer release exists, `ketch install` upgrades silently.

Plan:
1. Installed and a newer release resolves, with an unversioned spec: ask through `ui::confirm`: `<pkg> <installed> is installed; update to <latest>?`. The default answer is a decision for Ivan; the proposal is No, matching the other confirms. Yes runs the same path as `ketch upgrade <pkg>`, including the update hooks. No exits 0 with a note.
2. Installed and nothing newer: fail with `cannot install <pkg>: <version> is already installed and no update is available`, still exit 5. `--force` still reinstalls; whether its hint stays is a decision for Ivan.
3. `--yes` answers yes. Without a TTY and without `--yes`, fail and name `--yes` / `ketch upgrade`, instead of upgrading silently. This is a behaviour change, so it goes in `CHANGELOG.md`.
4. Pinned packages keep `Error::Pinned`. An explicit version (`pkg@1.2.0`) keeps today's behaviour. In a batch, each installed package is asked separately. `ketch sync` is unaffected.

Check: e2e with the mock release API: newer + yes → upgraded; newer + no → unchanged, exit 0; nothing newer → the message and exit 5; non-TTY without `--yes` → error; `docs/COMMANDS.md` updated; `just check`.

Done (Claude Code / opus-5.5): Ivan chose default No and to keep the `--force` hint. `InstallRequest.offer_update` (set by `ketch install` unless `--yes`) makes `install::prepare` stop an unversioned install of an installed package with `Error::UpdateAvailable` (newer release) or `Error::NoUpdate` (same release), both exit 5, before anything is downloaded; pinned packages and `pkg@version` keep their old errors. `cmd::pkg::install` asks after the parallel batch, one package at a time, and runs the approved ones as a second batch pinned to the exact tag the question named — the same prepare/commit path as `ketch upgrade`, update hooks included. Without a terminal the `UpdateAvailable` error stands, and its hint names `--yes` and `ketch upgrade <pkg>`. `ketch sync` and `self install` are unaffected (they never set the flag). Tests: e2e yes / no (on a pseudo-terminal through BSD `script`, macOS), `--yes`, no terminal, nothing newer; `docs/COMMANDS.md` updated.

Status: done 2026-09-30
