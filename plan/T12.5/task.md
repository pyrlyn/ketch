---
status: in progress
priority: P1
complexity: 2
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T12.5. Packaging

`packageDmg`, `packageMsi` and `packageDeb` with the native release `ketch` of the target
OS in the app's resources, where `locateKetch` finds it. Built and checked on the Mac (dmg),
the Linux and Windows formats where a host for them exists.

## Plan

1. `desktop/build.gradle.kts`: a `Sync` task puts the release `ketch` of the host's native
   target (`linkReleaseExecutable<Target>`) into `build/app-resources/<os>-<arch>/ketch`, and
   `appResourcesRootDir` points at it, so `locateKetch` finds it in the packaged app and in
   `:desktop:run`. Windows has no native target until T13, so its MSI ships without one.
2. Package metadata from the Rust apps: bundle id `io.github.pyrlyn.ketch`, the developer-tools
   category, the copyright line, `ketch://` in `CFBundleURLTypes` (the macOS app's Info.plist)
   so the system hands links to the open-URI handler; the Linux category and menu; on Windows
   a fixed upgrade UUID and a per-user install, matching the HKCU login item.
3. The JDK modules the app needs (`suggestRuntimeModules`; `java.prefs` for the settings), so
   the trimmed runtime has them.
4. Check on the Mac: `./gradlew build` green; `packageDmg` builds; the app in it carries the
   executable `ketch`, the URL type and the bundle id; run from a scratch copy with a scratch
   `HOME` and `KETCH_HOME` it starts `ketch serve` from its own resources without `KETCH_EXE`.
   `packageDeb` and `packageMsi` need a Linux or Windows host; none here.

Not here: an app icon (the Rust apps ship none either), a `ketch://` handler on Linux and
Windows (jpackage writes no scheme into the `.desktop` file or the MSI, and the WinUI app
registers none), signing, notarisation and release versions (T15).
