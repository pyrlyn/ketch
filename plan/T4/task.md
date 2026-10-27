---
status: in progress
priority: P0
complexity: 4
readiness: 12%
agent: Claude Code / claude-opus-5-5
---

# T4. Data model, config, manifests, state

`config`, `toml_file`, `log`, `report`, `model`, `manifest`, `extra`, `hooks`, `lockfile`,
`state`, `fsio`, `link`. Files Rust ketch writes are read by the port and the other way round;
round-trip tests over fixtures copied from `../ketch`.

## Plan

About 8.4k lines of Rust and 190 tests, with cycles between the modules (`model` ↔ `config` ↔
`manifest`, `report` ↔ `log` ↔ `config`), so the work lands in small green commits in this order:

1. `fs-kit` → `pyrlyn.kit:fs` in `packages/kotlin-kit` (package `fs`), the part ketch uses:
   - atomic write `Options`, `textOrEmpty`, `bytesIfExists`;
   - `removeAny`, `copyTree` with `Links`, `canon`, `canonicalize`;
   - `FsException` carrying the path, the kind and errno.

   It runs on POSIX over kotlinx-io paths. `fsio.writePrivate` sits on top, and the deferred T2
   `fs_kit` error test lands with it. rtok-kotlin's `rtok-sys` `Fs.kt` is not reused: its
   writes are not atomic, it ignores modes and links, and it removes a tree through
   `system("rm -rf")`.
2. `toml_file`: typed TOML read and write over `toml-ktoml` with kotlinx-serialization, and
   comment-keeping edits over `toml-aot`.
3. `model`: the package, asset and source types. The types it reads from later phases
   (`extract`, `trust`, `registry`, `source`) come in as the minimal declarations it needs.
4. `config`, `log`, `report`.
5. `manifest`, `extra`, `hooks`.
6. `lockfile`, `state`, `link`, with the insta snapshot as a fixture.

Every Rust test of these files is ported. Check: `./gradlew build` here and `./gradlew check` in
kotlin-kit green after each step.
