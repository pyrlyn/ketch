---
status: in progress
priority: P0
complexity: 4
readiness: 70%
agent: Claude Code / claude-opus-5-5
---

# T3. Shared Kotlin libraries

ketch needs TOML read and write plus a comment-keeping editor (Rust `toml`, `toml_edit`), YAML
read (`serde-saphyr`, winget manifests), semver (`semver`), SHA-256 (`sha2`), a blocking-style HTTP
client with proxy support (`ureq`) and SQLite (`diesel`, `stats.db`). rtok-kotlin already has
`libs/toml`, `toml-ktoml`, `toml-aot`, `yaml-aot`, `semver`, `http`, `http-ktor`, `db`, `db-room`.

The workspace rule says shared code is extracted into `packages/`, not copied. Done: each library
ketch needs is reused from a shared package (extracted from rtok-kotlin with the creator's OK) or
is a maintained third-party library behind our own interface; none is a copy.

## Plan

1. New included build `packages/kotlin-kit` (group `pyrlyn.kit`), laid out like kotlin-wasm: the
   nine rtok-kotlin libraries with their tests and detekt baselines, packages `rtok.toml`,
   `rtok.http`, `rtok.db` renamed to `toml`, `http`, `db`. Targets macosArm64, linuxX64,
   linuxArm64.
2. ketch-kotlin includes it (`KOTLIN_KIT_DIR` or `../../packages/kotlin-kit`); `:ketch-core`
   depends on `toml-ktoml`, `toml-aot`, `yaml-aot`, `semver`, `http-ktor`, `db-room`, and on
   `org.kotlincrypto.hash:sha2` for streaming SHA-256.
3. `SharedLibsTest` in `:ketch-core` proves each one resolves and works on the host target.
4. Check: `./gradlew check` in `packages/kotlin-kit` and `./gradlew :ketch-core:build` here green.
5. rtok-kotlin's switch to kotlin-kit is not part of this task (separate session).

Steps 1–3 written; step 4 pending.
