# T4. Data model, config, manifests, state — done

Ported from `../ketch` at 41738f64, with every Rust unit test of each file:

- `fsio`, `toml_file`, `text` helpers (Rust `trim`, ASCII case, `escape_debug`) over kotlin-kit's
  `fs` and `toml` (its strict TOML 1.1 `TomlParser`, `TomlWriter`, comment-keeping `toml-aot`).
- `model`: packages, assets, sources, repo names, archive member paths, extras, trust policy.
- `config`, `log`, `report`.
- `manifest` (resolver, built-in registry, user manifest writes, schema checks), `extra`
  (ketch's own man pages and completions) and `hooks` over the new `pyrlyn.kit:process`.
- `lockfile` (`ketch.lock`, byte for byte and against the insta snapshot and lock schema),
  `state` (`state.json` laid out as serde_json writes it, and the install-tree lock with its
  cross-process test) and `link` (`ketch://package/<name>`).

What moved to later tasks is listed in `task.md`.

Check:
- `./gradlew build` here: green; 278 native tests, ktlint and detekt clean.
- `-Pdiffuser.allTargets=true :ketch-core:compileKotlinLinuxX64 :ketch-core:compileKotlinLinuxArm64`:
  green.
- `./gradlew check` in `packages/kotlin-kit`: green.

Commits here: `9cfe9eb` … `1c0bc77` (`T4: …`). kotlin-kit: `cbcc4af` (toml parser), `110a098`
(process), `8db81fb` (fs: exclusive create, hard links, removal, modification times).
