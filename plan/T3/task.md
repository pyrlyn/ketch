---
status: todo
priority: P0
complexity: 4
readiness: 0%
agent:
---

# T3. Shared Kotlin libraries

ketch needs TOML read and write plus a comment-keeping editor (Rust `toml`, `toml_edit`), YAML
read (`serde-saphyr`, winget manifests), semver (`semver`), SHA-256 (`sha2`), a blocking-style HTTP
client with proxy support (`ureq`) and SQLite (`diesel`, `stats.db`). rtok-kotlin already has
`libs/toml`, `toml-ktoml`, `toml-aot`, `yaml-aot`, `semver`, `http`, `http-ktor`, `db`, `db-room`.

The workspace rule says shared code is extracted into `packages/`, not copied. Done: each library
ketch needs is reused from a shared package (extracted from rtok-kotlin with the creator's OK) or
is a maintained third-party library behind our own interface; none is a copy.
