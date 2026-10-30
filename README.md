# ketch-kotlin

A Kotlin port of [ketch](https://github.com/pyrlyn/ketch): install any CLI tool or app straight
from its GitHub releases — no formula, checked against the published checksum, versioned, and
cleanly removable.

- **`ketch`** — the command line, built with Kotlin/Native for macOS (arm64) and Linux
  (x86_64, arm64); Windows follows.
- **Ketch desktop** — one Compose Multiplatform app for macOS, Windows and Linux: Liquid Glass on
  macOS, Material 3 elsewhere. It runs the native `ketch` and talks to it over a JSON-lines protocol.

Status: early port. What is ported so far is tracked in [plan/README.md](plan/README.md).

## Build

Needs JDK 21 and four shared builds in the workspace's `packages/` (`../../packages/` from the
main checkout): `shared-settings` supplies the shared Kotlin/Native settings, ktlint, detekt and the
optional remote build cache; `kotlin-kit` supplies the TOML, YAML, semver, HTTP and SQLite
libraries; `kotlin-snapshot` supplies the snapshot assertions the tests use; `kotlin-glass` supplies
the components of the desktop app's macOS look. Every directory above the build is searched for
`packages/`, so a nested worktree finds them too; set `SHARED_SETTINGS_DIR`, `KOTLIN_KIT_DIR`,
`KOTLIN_SNAPSHOT_DIR` or `KOTLIN_GLASS_DIR` when they live elsewhere.

```bash
./gradlew build
./ketch/build/bin/macosArm64/debugExecutable/ketch.kexe --version
./gradlew :desktop:run
./gradlew :desktop:packageDmg
```

`-Pdiffuser.allTargets=true` adds the Linux targets. `mise run release` links the shipped
release binary. `packageDmg` (or `packageDeb`, `packageMsi` on those hosts) builds the desktop app
with the release `ketch` inside; `:desktop:run` uses the same one unless `KETCH_EXE` names another.

## License

The same terms as ketch: GPL-3.0-or-later, the ketch royalty-free licence or a commercial licence.
