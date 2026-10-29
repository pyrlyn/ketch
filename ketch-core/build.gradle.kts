// The port of crates/ketch-core: everything ketch does, printing nothing; front ends render its events.
plugins {
    id("ketch.kmp")
    // Typed queries over stats.db, checked against its schema at build time (Rust: diesel).
    id("app.cash.sqldelight")
}

kotlin {
    // Windows is ketch's own target, not the shared plugin's: it also builds rtok, which has none.
    mingwX64()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.coroutines.core)
            // Shared libraries from kotlin-kit: config and manifest TOML, winget YAML, versions,
            // GitHub over HTTP, stats.db.
            implementation("pyrlyn.kit:toml")
            implementation("pyrlyn.kit:toml-aot")
            implementation("pyrlyn.kit:yaml-aot")
            implementation("pyrlyn.kit:semver")
            implementation("pyrlyn.kit:http-ktor")
            implementation("pyrlyn.kit:db-sqldelight")
            // Streaming SHA-256 for downloads that do not fit in memory (Rust: sha2), behind ketch.core.hash.Sha256.
            implementation(libs.kotlincrypto.sha2)
        }
        // Crash-safe writes of config, state and lock files (Rust: fs-kit).
        nativeMain.dependencies {
            implementation("pyrlyn.kit:fs")
            // Hooks run under a deadline with capped output and a killable process group (Rust: run_with_deadline).
            implementation("pyrlyn.kit:process")
            // Tar, zip and the gzip, xz and bzip2 codecs the extractors unpack releases with (Rust: tar, zip,
            // flate2, lzma-rust2, bzip2).
            implementation("pyrlyn.kit:archive")
        }
        // stats.db's SQLite: androidx.sqlite's bundled build on macOS and Linux; it has no Windows build, so Windows
        // links SQLite's own C API.
        unixMain.dependencies { implementation("pyrlyn.kit:db-room") }
        mingwMain.dependencies { implementation("pyrlyn.kit:db-native") }
        // Checks the committed config and manifest schemas against what ketch accepts (Rust: jsonschema).
        nativeTest.dependencies {
            implementation(libs.json.schema.validator)
            // A hand-written HTTP server for the download guards: a lying or missing Content-Length.
            implementation("pyrlyn.kit:loopback")
        }
    }
}

sqldelight {
    // SQLite comes from db-room or db-native, never the system's: Windows has no libsqlite3 to link.
    linkSqlite.set(false)
    databases {
        create("StatsDatabase") {
            packageName.set("ketch.core.stats.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
        }
    }
}
