---
id: M14
title: JSON Schema for the package manifest
status: done
created_at: 1792583218
updated_at: 1792583218
---

`ketch.toml` (`Manifest` in `src/model.rs`) is the third TOML file ketch owns, after `config.toml` and `ketch.lock` (M13). `AGENTS.md` requires a schema for it too. Its nested types and custom (de)serializers (`PackageRef` as `scheme:id`, the `trust` and `hooks` tables) make it larger than M13.

Done when `docs/manifest.schema.json` is generated from `Manifest` with `config::assert_schema_current`, committed, checked by a drift test, and linked from `docs/MANIFESTS.md`. Every field a registry `ketch.toml` in `pyrlyn/ketch-registry` uses validates against it.

Plan:
1. `model.rs`: `cfg_attr(test, derive(schemars::JsonSchema))` on `Manifest` and every type it holds, the way M13 did `ConfigFile` and `Lockfile`. `PackageRef` is described as the string its `TryFrom<String>` accepts (a `pattern` for `scheme:id` or anything with a `/`), `ExtraPath` as the untagged string-or-table it is, `trust` and `hooks` as the closed tables `deny_unknown_fields` makes them. `name` is left out of `required`, because a registry package folder supplies it.
2. `docs/manifest.schema.json` from `config::assert_schema_current::<Manifest>`, with a drift test beside the M13 ones.
3. A test that validates the root `ketch.toml`, every `[[package]]` in `builtin.toml` and the examples in `docs/MANIFESTS.md` against the committed schema, with the `jsonschema` crate as a dev-dependency (maintained, draft 2020-12, no network with default features off); plus cases the deserializer rejects, so the schema is not looser than the reader where it can say so.
4. `docs/MANIFESTS.md`: link the schema and show the taplo `#:schema` directive; the root `ketch.toml` carries it. `toolchain.md` row for `jsonschema`.
5. Check against the live `pyrlyn/ketch-registry` files; `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/206. Schema generated and drift-tested; the root `ketch.toml`, `builtin.toml`, the docs examples and all six `pyrlyn/ketch-registry` files validate; a property test holds each pattern to its Rust check. Found on the way: `bin` entries and `[asset]` lack `deny_unknown_fields`, so a misspelt key there is still ignored (the schema follows ketch).
