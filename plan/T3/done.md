# T3. Shared Kotlin libraries — done

- New included build `packages/pyrlyn-kit` (group `pyrlyn.kit`), extracted from rtok-kotlin
  `libs/` at `48d9c31`:
  - nine modules: `toml`, `toml-ktoml`, `toml-aot`, `yaml-aot`, `semver`, `http`, `http-ktor`,
    `db`, `db-room`;
  - packages `rtok.toml`, `rtok.http` and `rtok.db` renamed to `toml`, `http` and `db`;
  - targets macosArm64, linuxX64 and linuxArm64.

  The package builds with `allWarningsAsErrors`, so a few warnings rtok tolerated were fixed;
  behaviour is unchanged.
- ketch-kotlin includes it through `KOTLIN_KIT_DIR`, or `../../packages/pyrlyn-kit` by default.
  `:ketch-core` depends on `toml-ktoml`, `toml-aot`, `yaml-aot`, `semver`, `http-ktor` and
  `db-room`.
- SHA-256: `org.kotlincrypto.hash:sha2` 0.8.0 behind `ketch.core.hash.Sha256`. It streams, which
  rtok-kotlin's whole-array `Sha256` does not.
- `SharedLibsTest` exercises each library and the hash wrapper on the host target.
- rtok-kotlin still uses its own `libs/`; its move to pyrlyn-kit is a separate session.

Check:
- `./gradlew check` in `packages/pyrlyn-kit`: 44 tests, ktlint and detekt green.
- `./gradlew build` here: green, including the 5 `SharedLibsTest` tests.
- `-Pdiffuser.allTargets=true :ketch-core:compileKotlinLinuxX64 :ketch-core:compileKotlinLinuxArm64`:
  green.

Commits: pyrlyn-kit `feat: extract shared libraries from rtok-kotlin`; here
`T3: shared libraries from pyrlyn-kit`.
