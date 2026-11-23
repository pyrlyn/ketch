<h1><picture><source media="(prefers-color-scheme: dark)" srcset="brand/logo-dark.svg"><img src="brand/logo.svg" width="32" height="32" alt="" align="absmiddle"></picture> ketch</h1>

Install any CLI tool or app straight from its GitHub releases — no formula, checked against the published checksum, versioned, and cleanly removable.

This is the Kotlin implementation of ketch. The earlier Rust tree lives in [pyrlyn-archive/ketch](https://github.com/pyrlyn-archive/ketch).

- **`ketch`** — the command line, built with Kotlin/Native for macOS (arm64) and Linux (x86_64, arm64); Windows follows.
- **Ketch desktop** — one Compose Multiplatform app for macOS, Windows and Linux: Liquid Glass on macOS, a Windows 11 look on Windows, a dark Adwaita-style look on Linux. It runs the native `ketch` and talks to it over a JSON-lines protocol.

Status: early port. What is ported so far is tracked in [plan/README.md](plan/README.md).

## Quick start

Needs JDK 21 and the shared builds in the workspace's `packages/` ([details](docs/building.md)).

```bash
./gradlew build
./ketch/build/bin/macosArm64/debugExecutable/ketch.kexe --version
./gradlew :desktop:run
```

The binary path above is the Kotlin/Native debug executable on macOS arm64. Linux paths and packaging are in [Building](docs/building.md).

## Example

```bash
ketch install owner/repo     # install from a GitHub repository's releases
ketch list                   # installed and available packages
ketch upgrade                # every unpinned package to its latest release
ketch uninstall repo         # remove it again
```

`ketch --help` and `ketch <command> --help` list every option. The command set also includes `update`, `outdated`, `info`, `search`, `pin`, `rollback`, `doctor`, `self`, and others.

## Documentation

- [Building](docs/building.md): toolchain, shared builds, Gradle and mise tasks, packaging, tests.
- [Architecture](docs/architecture.md): modules and how they fit together.
- [Contributing](docs/contributing.md): rules for changes.
- Desktop end-to-end screenshots: `./gradlew :desktop:e2e` (see [Building](docs/building.md)).

## Releasing

The version has one source: `VERSION` in `ketch-core/src/commonMain/kotlin/ketch/core/Version.kt`.
Bump it through a PR, then run the `release` workflow on `main` with `dry-run` off. It tests,
links and smoke-tests the macOS arm64 and Linux x64 binaries, and creates a draft prerelease
`v<VERSION>` on that main commit with the tarballs, their `.sha256` files and `SHA256SUMS`;
publishing the draft creates the tag. It refuses a version whose tag already exists. CI
(`.github/workflows/ci.yml`) builds and tests both targets on every push and pull request.

## License

No license file is present in this repository. Desktop package metadata records
`GPL-3.0-or-later OR royalty-free OR commercial licence`.
