---
status: in progress
priority: P0
complexity: 2
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T1. Build scaffolding

The Gradle build every later phase lands in: modules `:ketch-core`, `:ketch-wire`, `:ketch`,
`:desktop`, ci-diffuser settings, convention plugins, project docs.

Done: `./gradlew build` is green, links `ketch.kexe` (`--version` prints `ketch 0.0.1 · preview`,
as Rust ketch does) and builds the desktop app.

## Plan

1. `settings.gradle.kts` and root build on ci-diffuser (settings, native, ktlint, detekt), as
   octo and rtok-kotlin do.
2. `buildSrc` conventions `ketch.kmp` (native, optional jvm) and `ketch.compose` (desktop).
3. Stub sources and one test per module; `AGENTS.md`, `README.md`, `toolchain.md`, `plan/`.
4. Check: `./gradlew build`, run the binary.
