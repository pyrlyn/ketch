# T1. Done

Gradle build on ci-diffuser (settings, native targets, release size, ktlint, detekt) with
`buildSrc` conventions `ketch.kmp` and `ketch.compose`; modules `:ketch-core`, `:ketch-wire`
(native + jvm), `:ketch` and `:desktop`; project docs and the phase plan in `plan/`.

Checked: `./gradlew build` green on macOS arm64; `ketch.kexe -V` prints `ketch 0.0.1 · preview`.
Commit: `T1: build scaffolding`.
