---
status: in progress
priority: P0
complexity: 4
readiness: 100%
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
2. `toml_file`: typed TOML read and write over kotlin-kit's `toml` (`TomlParser`, `TomlWriter`) with kotlinx-serialization, and
   comment-keeping edits over `toml-aot`.
3. `model`: the package, asset and source types. The types it reads from later phases
   (`extract`, `trust`, `registry`, `source`) come in as the minimal declarations it needs.
4. `config`, `log`, `report`.
5. `manifest`, `extra`, `hooks`.
6. `lockfile`, `state`, `link`, with the insta snapshot as a fixture.

Every Rust test of these files is ported. Check: `./gradlew build` here and `./gradlew check` in
kotlin-kit green after each step.

Moved out of the `model` step:

- `CompletionShell`'s clap mapping: the CLI phase. `write_ketch_docs` landed in the `manifest`
  step, taking the man pages and completions as functions the CLI will pass in.
- Parsing minisign and gpg keys in `check_policy`: T14.
- The schema tests and `BUILTIN_TOML`: the `manifest` step. `committed_manifest_schema_matches_manifest`
  is not ported: there the Rust types are the schema's source.
- `the_old_self_repo_and_registry_names_load_as_the_new_ones`: the `config` step.
- The Windows-only `usable_file_name` test: T13. The proptest is a fixed sample.

Moved out of the `config` step:

- `committed_config_schema_matches_config_file` and `the_schema_lists_the_log_values`: the
  `manifest` step, with the other schema tests.
- The non-Unicode branch of `env_bool`: reached only through a raw `setenv`, which the tests do
  not set up; the code path is ported.

Notes on the `manifest`, `extra`, `hooks` step:

- `Resolver.load` takes the registry's manifests as a parameter until T7 ports `registry.load`.
- The manifest and config schemas, `MANIFESTS.md`, `REGISTRY.md` and the repository's
  `ketch.toml` are fixtures in `ketch-core/fixtures/rust`, copied from `../ketch` at 41738f64.
  The manifest schema test compares the schema's keys with the serial names; the pattern
  proptest is a fixed sample of strings.
- Hooks run through the new `pyrlyn.kit:process` (no stdin, capped output, process-group kill at
  the deadline); T7's plugin source reuses it. The Windows `cmd /C` shell: T13.

Notes on the `lockfile`, `state`, `link` step:

- `lock.schema.json` and the insta snapshot `lockfile-two-packages.snap` are fixtures copied
  from `../ketch` at 41738f64; the lockfile proptest is a seeded sample.
- Unknown keys are skipped per type (`@JsonIgnoreUnknownKeys`) exactly where Rust's serde
  skips them; types that deny them in Rust still refuse them.
- The cross-process lock test reruns the test binary filtered to one contender test
  (`--ktest_filter`); its children prove they ran by writing a file.
- kotlin-kit's `fs` gained exclusive create, hard links, file removal and modification times
  for the lock. The Windows `tasklist` liveness check: T13.
