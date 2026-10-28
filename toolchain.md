# Toolchain

| Program | How to install | Why here | Source |
| --- | --- | --- | --- |
| JDK 21 (Temurin) | mise | Runs Gradle, the Kotlin daemon and the desktop app; its jpackage builds the app's dmg, deb and msi | https://adoptium.net |
| Gradle 9.8.2 | wrapper (`./gradlew`) | Build | https://github.com/gradle/gradle |
| Kotlin 2.4.21 | Gradle plugin | Language, Kotlin/Native, Kotlin Multiplatform | https://github.com/JetBrains/kotlin |
| ci-diffuser | sibling checkout at `../ci-diffuser` (or `CI_DIFFUSER_DIR`), local included build | Shared targets, release size, ktlint, detekt, settings | `apps/ci-diffuser` (no remote) |
| kotlin-kit | checkout at `../../packages/kotlin-kit` (or `KOTLIN_KIT_DIR`), local included build | File, TOML, YAML, semver, HTTP, SQLite and child-process libraries shared with rtok-kotlin | `packages/kotlin-kit` (no remote) |
| kotlin-snapshot | checkout at `../../packages/kotlin-snapshot` (or `KOTLIN_SNAPSHOT_DIR`), local included build | insta-format snapshot assertions | `packages/kotlin-snapshot` (no remote) |
| git | system | Version control | https://git-scm.com |
| conan 2.33.0 | mise (kotlin-kit) | Fetches the static bzip2 and liblzma that kotlin-kit's `archive` links | https://github.com/conan-io/conan |
| zlib | Kotlin/Native platform library (`platform.zlib`), via kotlin-kit | gzip and deflate in archives | https://zlib.net |
| hdiutil, pkgutil, ditto | macOS system | Unpacking `.dmg` and `.pkg` releases | https://developer.apple.com |

## Gradle

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| org.jetbrains.kotlinx:kotlinx-serialization-json 1.12.0 | local | https://github.com/Kotlin/kotlinx.serialization | JSON: `state.json`, GitHub API, the desktop protocol |
| org.jetbrains.kotlinx:kotlinx-io-core 0.9.1 | local | https://github.com/Kotlin/kotlinx-io | Files and paths in common code |
| org.jetbrains.kotlinx:kotlinx-coroutines-core 1.11.0 | local | https://github.com/Kotlin/kotlinx.coroutines | Concurrency, cancellation |
| org.jetbrains.kotlinx:kotlinx-coroutines-swing 1.11.0 | local | https://github.com/Kotlin/kotlinx.coroutines | Main dispatcher for the desktop app |
| org.jetbrains.kotlinx:kotlinx-coroutines-test 1.11.0 | local | https://github.com/Kotlin/kotlinx.coroutines | Desktop tests |
| org.kotlincrypto.hash:sha2 0.8.0 | local | https://github.com/KotlinCrypto/hash | Streaming SHA-256 of downloads (Rust: sha2) |
| pyrlyn.kit:fs, toml, toml-aot, yaml-aot, semver, http-ktor, db-room, db-sqldelight, process, archive, cli, icons | local (included build) | `packages/kotlin-kit` | Crash-safe file writes (Rust: fs-kit), TOML read and comment-keeping edit, YAML read, versions, HTTP, SQLite and SQLDelight on it, hooks under a deadline (Rust: run_with_deadline), tar/zip/gzip/bzip2/xz (Rust: tar, zip, flate2, bzip2, xz2), the command line with clap's help and errors, completion scripts and man pages (Rust: clap, clap_complete, clap_mangen), status-line icons (Rust: operation-icons) |
| pyrlyn.snapshot:snapshot | local (included build, tests only) | `packages/kotlin-snapshot` | Rust ketch's `.snap` files, read as they are (Rust: insta) |
| pyrlyn.kit:loopback | local (included build, tests only) | `packages/kotlin-kit` | A hand-written HTTP server on 127.0.0.1 for the download guards (Rust tests: a raw `TcpListener`) |
| io.ktor:ktor-client-core, -darwin, -curl 3.6.0 | via kotlin-kit | https://github.com/ktorio/ktor | HTTP client (Rust: ureq) |
| androidx.sqlite:sqlite-bundled 2.7.1 | via kotlin-kit | https://developer.android.com/jetpack/androidx/releases/sqlite | SQLite with SQLite bundled (Rust: libsqlite3-sys, bundled) |
| app.cash.sqldelight (Gradle plugin, runtime, sqlite-3-38-dialect) 2.4.1 | local; runtime via kotlin-kit | https://github.com/sqldelight/sqldelight | `stats.db` queries typed and checked against its schema at build time (Rust: diesel) |
| io.kotest:kotest-assertions-core 6.2.6 | local | https://github.com/kotest/kotest | Test assertions |
| io.github.optimumcode:json-schema-validator 0.5.5 | local | https://github.com/OptimumCode/json-schema-validator | Tests: manifests and config against the schemas Rust ketch publishes (Rust: jsonschema) |
| org.jetbrains.kotlin:kotlin-test | local | https://github.com/JetBrains/kotlin | Tests |
| Compose Multiplatform 1.12.2 (`org.jetbrains.compose`) | local | https://github.com/JetBrains/compose-multiplatform | Desktop UI, and its UI tests (`compose.uiTest`) |
| com.mikepenz:multiplatform-markdown-renderer-m3 0.46.0 | local | https://github.com/mikepenz/multiplatform-markdown-renderer | Changelog Markdown in the desktop app (macOS app: `AttributedString(markdown:)`) |
| org.jlleitschuh.gradle.ktlint 14.2.0 (ktlint 1.8.0) | via ci-diffuser | https://github.com/JLLeitschuh/ktlint-gradle | Formatting lint |
| dev.detekt 2.0.0-alpha.6 | via ci-diffuser | https://github.com/detekt/detekt | Static analysis |
| com.github.burrunan.s3-build-cache 1.9.10 | local (settings) | https://github.com/burrunan/gradle-s3-build-cache | Optional S3/R2 remote build cache; off without its keys |

## Conan (ConanCenter, via kotlin-kit)

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| bzip2 1.0.8 | via kotlin-kit (`archive/conan.lock`) | https://sourceware.org/bzip2/ | bzip2 codec, linked statically |
| xz_utils 5.8.4 | via kotlin-kit (`archive/conan.lock`) | https://github.com/tukaani-project/xz | xz decoder (liblzma), linked statically |

## mise

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| java temurin-21 | global | https://adoptium.net | JDK |
| gradle 9.8.2 | global | https://github.com/gradle/gradle | Same as the wrapper |
