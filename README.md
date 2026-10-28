# ketch-kotlin

A Kotlin port of [ketch](https://github.com/pyrlyn/ketch): install any CLI tool or app straight
from its GitHub releases — no formula, checked against the published checksum, versioned, and
cleanly removable.

- **`ketch`** — the command line, built with Kotlin/Native for macOS (arm64) and Linux
  (x86_64, arm64); Windows follows.
- **Ketch desktop** — one Compose Multiplatform app for macOS, Windows and Linux. It runs the
  native `ketch` and talks to it over a JSON-lines protocol.

Status: early port. What is ported so far is tracked in [plan/README.md](plan/README.md).

## Build

Needs JDK 21 and a checkout of `ci-diffuser` at `../ci-diffuser` (or `CI_DIFFUSER_DIR`), which
supplies the shared Kotlin/Native settings, ktlint, detekt and the optional remote build cache, and
a checkout of `kotlin-kit` at `../../packages/kotlin-kit` (or `KOTLIN_KIT_DIR`), which supplies the
TOML, YAML, semver, HTTP and SQLite libraries.

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
