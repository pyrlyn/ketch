# ketch-kotlin

A Kotlin port of [ketch](https://github.com/pyrlyn/ketch): install any CLI tool or app straight
from its GitHub releases — no formula, checked against the published checksum, versioned, and
cleanly removable.

- **`ketch`** — the command line, built with Kotlin/Native for macOS (arm64) and Linux
  (x86_64, arm64); Windows follows.
- **Ketch desktop** — one Compose Multiplatform app for macOS, Windows and Linux: Liquid Glass on
  macOS, a Windows 11 look on Windows, a dark Adwaita-style look on Linux. It runs the native `ketch` and talks to it over a JSON-lines protocol.

Status: early port. What is ported so far is tracked in [plan/README.md](plan/README.md).

## Quick start

Needs JDK 21 and the shared builds in the workspace's `packages/` ([details](docs/building.md)).

```bash
./gradlew build
./ketch/build/bin/macosArm64/debugExecutable/ketch.kexe --version
./gradlew :desktop:run
```

## Example

```bash
ketch install owner/repo     # install from a GitHub repository's releases
ketch list                   # installed and available packages
ketch upgrade                # every unpinned package to its latest release
ketch uninstall repo         # remove it again
```

The command line matches Rust ketch; `ketch --help` and `ketch <command> --help` list every option.

## Documentation

- [Building](docs/building.md): toolchain, shared builds, Gradle and mise tasks, packaging, tests.
- [Architecture](docs/architecture.md): modules and how they fit together.
- [Contributing](docs/contributing.md): rules for changes.

## License

The same terms as ketch: GPL-3.0-or-later, the ketch royalty-free licence or a commercial licence.
