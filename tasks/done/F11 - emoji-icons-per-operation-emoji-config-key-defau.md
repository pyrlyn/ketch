---
id: F11
title: Emoji icons per operation, `emoji` config key (default true)
status: done
created_at: 1792583854
updated_at: 1792583854
---

Plan:
1. One table in `src/ui.rs` maps each operation to an icon (proposal: install 📦, upgrade ⬆️, uninstall 🗑️, download ⬇️, link 🔗, rollback ⏪, search 🔍, doctor 🩺, success ✅, warning ⚠️, error ❌, note ℹ️). Ivan picks the final set.
2. Config: `emoji = true` in `Config` / `Config::default_toml()`, the `KETCH_EMOJI` env var, and a `--no-emoji` global flag if wanted. Document it in the Configuration table in `README.md` and `docs/COMMANDS.md`, and in the `config reset` defaults test.
3. Icons appear only on human-facing status lines going to a terminal. They never appear in `--json`, `--names-only`, `ui::out` data, the log file, or when `TERM=dumb`.
4. Width: emoji are double-width, so pad the verb column with `unicode-width` and keep columns aligned with and without icons.

Check: snapshots with emoji on and off; JSON and piped output contain no emoji; `emoji = false` and `KETCH_EMOJI=0` turn them off; `just check`.

Execution plan (Claude Code / opus-5.5, done), with Ivan's final set: the proposal above as written.

1. `src/ui.rs`: an `EMOJI` switch beside `COLOR`, set by `ui::set_emoji` once the config is loaded; on only when wanted and stderr is a terminal whose `TERM` is not `dumb`. One icon table next to `Tone`: operation icons matched on the verb, then the tone's own icon (success, warning, error, note). The icon is padded to two columns with `unicode-width`, and lines without an icon get the same blank gutter, so every column stays aligned.
2. `src/config.rs`: `emoji` in `ConfigFile`, `Config` and `default_toml()`, `KETCH_EMOJI` through `env_bool`. `src/cli.rs`: global `--no-emoji`. `src/main.rs`: call `ui::set_emoji` after each `Config::load`.
3. Tests: insta snapshots of every line kind with emoji on and off; the resolver as a pure function (config off, `KETCH_EMOJI=0`, flag, pipe, `TERM=dumb`); e2e: piped stderr and `--json` carry no emoji; `config reset` defaults include `emoji = true`.
4. Docs: `README.md` Configuration table, `docs/COMMANDS.md` global flags. `Cargo.toml`: `unicode-width` as a direct dependency (already locked through indicatif), `toolchain.md` and `rust.md` rows.

Result: `src/ui.rs` holds one icon table beside `Tone`: an operation icon picked from the verb (install 📦, upgrade/update ⬆️, uninstall/remove/prune 🗑️, download/fetch ⬇️, link 🔗, rollback ⏪, search 🔍, doctor 🩺), else the tone's icon (success ✅, warning ⚠️, error ❌, note ℹ️); steps, debug lines and prompts get a blank gutter of the same width, measured with `unicode-width` (now a direct dependency), so the verb column stays aligned with and without icons. `emoji` (default `true`) is in `Config` and `default_toml()`, `KETCH_EMOJI` overrides it, and the global `--no-emoji` turns it off. `ui::set_emoji` runs after the config loads and keeps icons off a pipe and `TERM=dumb`; they never touch `ui::out`, tables, `--json`, `--names-only` or the log. Tests: insta snapshots of every line kind with emoji on and off, the resolver cases, icon widths and column alignment, config file and env tests, `config reset` writing `emoji = true`, and an end-to-end install whose piped output, `--json` and log carry no icon. Skipped: a schemars schema for `config.toml`, which the repository does not have for any key yet. `search` and `doctor` print data rather than status lines today, so their icons have no line to appear on until one is added.
