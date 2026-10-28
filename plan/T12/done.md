# T12. Compose desktop app: done

`:desktop` is one Compose Multiplatform app (JVM). It replaces the SwiftUI and WinUI apps,
runs on `ketch serve` and ships with the native `ketch` inside. The five subtasks each have
their own report:

- `T12.1/done.md`: the client of `ketch serve`, and the fake core that replays the contract
  scenarios.
- `T12.2/done.md`: the store, with state, operations, progress, busy and retry, questions,
  the log and search.
- `T12.3/done.md`: the window and its screens, Material 3 on the brand tokens, and
  `ketch://` links.
- `T12.4/done.md`: Settings, About, the tray, open at login and update notices.
- `T12.5/done.md`: dmg, deb and msi packaging with the release `ketch` and the `ketch://`
  URL type.

Decisions that apply across T12, as the creator made them when it was claimed:
- Material 3 on `desktop/design/tokens.json`, with no imitation of Liquid Glass.
- No self-updates (Sparkle).
- The native `ketch` ships in the package, and `KETCH_EXE` overrides it.

Left for later tasks:
- The Windows `ketch` inside the MSI waits for T13.
- Signing, notarisation and release versions belong to T15.

Not ported:
- Settings → Appearance.
- An app icon (the Rust apps ship none).
- A `ketch://` handler on Linux and Windows.

Checked on the Mac:
- `./gradlew build` is green, with 106 desktop tests.
- The dmg builds, and the app taken from it starts its own `ketch serve`.
- `deb` and `msi` were not built, since there is no Linux or Windows host here.
- Screen access was declined, so no one has looked at the window.
