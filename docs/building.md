# Building

Needs JDK 21 and six shared builds in the workspace's `packages/` (`../../packages/` from the
main checkout): `shared-settings` supplies the shared Kotlin/Native settings, ktlint, detekt and the
optional remote build cache; `kotlin-kit` supplies the TOML, YAML, semver, HTTP and SQLite
libraries; `kotlin-snapshot` supplies the snapshot assertions the tests use; `kotlin-glass`, `kotlin-fluent` and
`kotlin-adwaita` supply the components of the desktop app's macOS, Windows and Linux looks. Every directory above the build is searched for
`packages/`, so a nested worktree finds them too; set `SHARED_SETTINGS_DIR`, `KOTLIN_KIT_DIR`,
`KOTLIN_SNAPSHOT_DIR`, `KOTLIN_GLASS_DIR`, `KOTLIN_FLUENT_DIR` or `KOTLIN_ADWAITA_DIR` when they live
elsewhere.

```bash
./gradlew build
./ketch/build/bin/macosArm64/debugExecutable/ketch.kexe --version
./gradlew :desktop:run
./gradlew :desktop:packageDmg
```

`-Pdiffuser.allTargets=true` adds the Linux targets. `mise run release` links the shipped
release binary. `packageDmg` (or `packageDeb`, `packageMsi` on those hosts) builds the desktop app
with the release `ketch` inside; `:desktop:run` uses the same one and `:desktop:hotRun` the debug one,
unless `KETCH_EXE` names another.

## mise tasks

| Task | Runs |
| --- | --- |
| `mise run release` | `./gradlew :ketch:linkReleaseExecutableMacosArm64`: the shipped release binary, with full whole-program optimization. |
| `mise run release-fast` | The same link from the Kotlin/Native release binary cache; for development and CI only, never shipped. |
| `mise run lint` | `./gradlew ktlintCheck detekt` |
| `mise run format` | `./gradlew ktlintFormat` |

## Tests

```bash
./gradlew build                    # everything: compile, tests, ktlint, detekt
./gradlew macosArm64Test           # native unit tests
```

Tests never touch the real `HOME`: the build points `HOME` and `KETCH_HOME` at `build/test-home`
for every native test run, and no test uses the network.
