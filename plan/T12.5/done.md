# T12.5. Done

`desktop/build.gradle.kts` packages the app with jpackage (Compose's `nativeDistributions`).

- `bundledKetch` (a `Sync`) puts the host's release `ketch` (`linkReleaseExecutable<Target>`)
  into `build/app-resources/<os>-<arch>/ketch`, and `appResourcesRootDir` points there. The
  packaged app and `:desktop:run` find it through `compose.application.resources.dir`, as
  `locateKetch` expects; `KETCH_EXE` still overrides it.
- jpackage drops file modes when it copies resources into the app image, so a step after
  `createDistributable` makes the bundled `ketch` executable again. The app's ad hoc signature
  stays valid (`codesign -v --deep --strict`); a mode is not part of it.
- Metadata from the Rust apps: bundle id `io.github.pyrlyn.ketch`, the developer-tools
  category, the description, the licence line, and `ketch://` in `CFBundleURLTypes` as the
  macOS app's Info.plist has it, so the system hands links to the open-URI handler. Linux: the
  `ketch` package name, the Development category and menu. Windows: a per-user install (as the
  HKCU login item is), a fixed upgrade UUID, a menu group and a shortcut.
- The trimmed runtime takes the modules `suggestRuntimeModules` names (`java.instrument`,
  `java.prefs`, `jdk.unsupported`); without `java.prefs` the settings would not load.
- The version stays `1.0.0`: jpackage refuses a macOS version that starts at 0, and release
  versions belong to T15.
- README, AGENTS.md and toolchain.md name `packageDmg` and jpackage.

Not done: `packageDeb` and `packageMsi` were not built, since there is no Linux or Windows host
here. Windows has no native target until T13, so an MSI built now has no `ketch` inside and the
app says so when it starts. No app icon (the Rust apps ship none). There is no `ketch://`
handler on Linux or Windows: jpackage writes no scheme into the `.desktop` file or the MSI, and
the WinUI app registers none. No signing or notarisation (T15).

Commits: `f0ff467 T12.5: claim and plan`, `6d31521 T12.5: package the app with the release
ketch, its bundle id and the ketch:// URL type`.

Checked on the Mac:
- `./gradlew build` is green, and `./gradlew :desktop:packageDmg` builds `Ketch-1.0.0.dmg`.
- Inside the dmg, `Ketch.app` holds an executable `Contents/app/resources/ketch` that reports
  `ketch 0.0.1 · preview`. Its Info.plist has the bundle id, the category and the
  `ketch` URL scheme, and the launcher config sets `jpackage.app-version`.
- A copy taken from the mounted dmg, run with a scratch `HOME` and `KETCH_HOME` and no
  `KETCH_EXE`, started `…/Ketch.app/Contents/app/resources/ketch serve`, and the core ended
  when the app was stopped.
- Running the copy registered it with LaunchServices; it was unregistered with
  `lsregister -u` and the copy deleted.
- The window was not looked at.
