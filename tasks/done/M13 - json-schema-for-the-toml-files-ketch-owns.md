---
id: M13
title: JSON Schema for the TOML files ketch owns
status: done
created_at: 1792583854
updated_at: 1792583854
---

`AGENTS.md` requires every config file this project owns to have a schema generated from its types with `schemars`, committed and checked by a drift test. There was none. Requested by the creator in chat.

Done when `config.toml` (`ConfigFile`) and `ketch.lock` (`Lockfile`) each have a committed JSON Schema generated from their types, and a test fails when a committed schema differs from the generated one.

Done (Claude Code / opus-5.5):

1. `schemars` 1.2 as a dev-dependency: only the tests export a schema, so the derives are `cfg_attr(test, ...)` and the binary does not change. Row in `toolchain.md`.
2. Derive `JsonSchema` on `ConfigFile`, `Lockfile`, `LockedPackage`; `PackageRef` is stored as a `scheme:id` string, so its fields use `#[schemars(with = "String")]`.
3. Commit `docs/config.schema.json` and `docs/lock.schema.json`.
4. Drift tests beside the types (the crate has no library target, so `tests/` cannot reach them): `config::assert_schema_current` generates the schema, drops `null` from `type` (TOML has none), and compares with the committed file; `KETCH_BLESS=1 cargo nextest run schema` rewrites it.
5. Checked: `cargo nextest run` (709 passed), `cargo clippy --all-targets -D warnings`, `cargo fmt --check`; a hand edit to a schema fails its test.

Not in scope: the package manifest (`ketch.toml`, `Manifest` in `model.rs`) — noted in `ideas.md`.

Status: done 2026-09-30
Model: Claude Code / opus-5.5
