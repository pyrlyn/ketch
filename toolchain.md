# Toolchain

| Program | How to install | Why here | Source |
| --- | --- | --- | --- |
| JDK 21 (Temurin) | mise | Runs Gradle, the Kotlin daemon and the desktop app | https://adoptium.net |
| Gradle 9.8.2 | wrapper (`./gradlew`) | Build | https://github.com/gradle/gradle |
| Kotlin 2.4.21 | Gradle plugin | Language, Kotlin/Native, Kotlin Multiplatform | https://github.com/JetBrains/kotlin |
| ci-diffuser | sibling checkout at `../ci-diffuser` (or `CI_DIFFUSER_DIR`), local included build | Shared targets, release size, ktlint, detekt, settings | `apps/ci-diffuser` (no remote) |
| kotlin-kit | checkout at `../../packages/kotlin-kit` (or `KOTLIN_KIT_DIR`), local included build | File, TOML, YAML, semver, HTTP and SQLite libraries shared with rtok-kotlin | `packages/kotlin-kit` (no remote) |
| git | system | Version control | https://git-scm.com |

## Gradle

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| org.jetbrains.kotlinx:kotlinx-serialization-json 1.12.0 | local | https://github.com/Kotlin/kotlinx.serialization | JSON: `state.json`, GitHub API, the desktop protocol |
| org.jetbrains.kotlinx:kotlinx-io-core 0.9.1 | local | https://github.com/Kotlin/kotlinx-io | Files and paths in common code |
| org.jetbrains.kotlinx:kotlinx-coroutines-core 1.11.0 | local | https://github.com/Kotlin/kotlinx.coroutines | Concurrency, cancellation |
| org.jetbrains.kotlinx:kotlinx-coroutines-swing 1.11.0 | local | https://github.com/Kotlin/kotlinx.coroutines | Main dispatcher for the desktop app |
| org.jetbrains.kotlinx:kotlinx-coroutines-test 1.11.0 | local | https://github.com/Kotlin/kotlinx.coroutines | Desktop tests |
| com.github.ajalt.clikt:clikt 5.1.0 | local | https://github.com/ajalt/clikt | Command line (Rust: clap) |
| org.kotlincrypto.hash:sha2 0.8.0 | local | https://github.com/KotlinCrypto/hash | Streaming SHA-256 of downloads (Rust: sha2) |
| pyrlyn.kit:fs, toml-ktoml, toml-aot, yaml-aot, semver, http-ktor, db-room | local (included build) | `packages/kotlin-kit` | Crash-safe file writes (Rust: fs-kit), TOML read and comment-keeping edit, YAML read, versions, HTTP, SQLite |
| com.akuleshov7:ktoml-core 0.7.1 | via kotlin-kit | https://github.com/orchestr7/ktoml | TOML parser (Rust: toml) |
| io.ktor:ktor-client-core, -darwin, -curl 3.6.0 | via kotlin-kit | https://github.com/ktorio/ktor | HTTP client (Rust: ureq) |
| androidx.sqlite:sqlite-bundled 2.7.1 | via kotlin-kit | https://developer.android.com/jetpack/androidx/releases/sqlite | SQLite with SQLite bundled (Rust: diesel) |
| io.kotest:kotest-assertions-core 6.2.6 | local | https://github.com/kotest/kotest | Test assertions |
| org.jetbrains.kotlin:kotlin-test | local | https://github.com/JetBrains/kotlin | Tests |
| Compose Multiplatform 1.12.2 (`org.jetbrains.compose`) | local | https://github.com/JetBrains/compose-multiplatform | Desktop UI |
| org.jlleitschuh.gradle.ktlint 14.2.0 (ktlint 1.8.0) | via ci-diffuser | https://github.com/JLLeitschuh/ktlint-gradle | Formatting lint |
| dev.detekt 2.0.0-alpha.6 | via ci-diffuser | https://github.com/detekt/detekt | Static analysis |
| com.github.burrunan.s3-build-cache 1.9.10 | local (settings) | https://github.com/burrunan/gradle-s3-build-cache | Optional S3/R2 remote build cache; off without its keys |

## mise

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| java temurin-21 | global | https://adoptium.net | JDK |
| gradle 9.8.2 | global | https://github.com/gradle/gradle | Same as the wrapper |
