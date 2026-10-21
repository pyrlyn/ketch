---
id: F5
title: Config reset and shared file backup — done (ketch side)
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ketch config reset` writes `config.toml` with compiled defaults after confirming, backing the existing file up beside itself as `config.toml.bak-<unix-seconds>` via the `file-backup` crate. `ConfigCommand::Reset { yes }`, `Config::default_toml()`, `reset()` in `src/cmd/config.rs`, e2e in `tests/config_reset.rs`, docs in README Configuration and `docs/COMMANDS.md`. Unit tests `default_toml_parses_back_to_compiled_defaults` and `a_reset_file_loads_back_to_the_effective_defaults` in `src/config.rs` (plus the `ENV_GUARD`/`CleanEnv` fix for the flaky token-fallback test).

Not done (needs rtok owner): `rtok-agent-sdk::backup` still has its own copy and does not re-export the shared crate.
