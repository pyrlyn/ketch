---
id: M15
title: "`log_level` and `log_format` as enums in `config.toml`"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`ConfigFile` holds both as free strings and checks them at load time, so the schema cannot list the allowed values. `AGENTS.md`: constraints live in the types.

Done when `ConfigFile` uses `crate::log::Level` and `crate::log::Format` (serde, lower case) directly, a bad value still fails with an error naming the file and the key, `docs/config.schema.json` lists the values, and the environment variables keep working as before.


Execution plan: give `Level` and `Format` in `crates/ketch-core/src/log.rs` serde (lower case) and `schemars` derives; switch `ConfigFile.log_level` and `log_format` in `crates/ketch-core/src/config.rs` to `Option<Level>` and `Option<Format>`; keep the environment variables parsed by `FromStr` as before; make the TOML parse error name the file and the key; regenerate `docs/config.schema.json` with the existing schema export; add tests for a bad value, each environment variable, the schema enum and unchanged loading of valid files; then run fmt, clippy, nextest and `ketch doctor` against a scratch root.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/203.
