# ketch-kotlin

A Kotlin port of [ketch](https://github.com/pyrlyn/ketch): install any CLI tool or app straight
from its GitHub releases — no formula, checked against the published checksum, versioned, and
cleanly removable.

- **`ketch`** — the command line, built with Kotlin/Native for macOS (arm64) and Linux
  (x86_64, arm64); Windows follows.
- **Ketch desktop** — one Compose Multiplatform app for macOS, Windows and Linux: Liquid Glass on
  macOS, a Windows 11 look on Windows, a dark Adwaita-style look on Linux. It runs the native `ketch` and talks to it over a JSON-lines protocol.

Status: early port. What is ported so far is tracked in [plan/README.md](plan/README.md).

## Build

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

## Releasing

The version has one source: `VERSION` in `ketch-core/src/commonMain/kotlin/ketch/core/Version.kt`.
Bump it through a PR, then run the `release` workflow on `main` with `dry-run` off. It tests,
links and smoke-tests the macOS arm64 and Linux x64 binaries, and creates a draft prerelease
`v<VERSION>` on that main commit with the tarballs, their `.sha256` files and `SHA256SUMS`;
publishing the draft creates the tag. It refuses a version whose tag already exists. CI
(`.github/workflows/ci.yml`) builds and tests both targets on every push and pull request.

## License

The same terms as ketch: GPL-3.0-or-later, the ketch royalty-free licence or a commercial licence.
