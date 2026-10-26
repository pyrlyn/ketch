// The port of crates/ketch-core: everything ketch does, printing nothing; front ends render its events.
plugins { id("ketch.kmp") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.coroutines.core)
            // Shared libraries from kotlin-kit: config and manifest TOML, winget YAML, versions,
            // GitHub over HTTP, stats.db.
            implementation("pyrlyn.kit:toml-ktoml")
            implementation("pyrlyn.kit:toml-aot")
            implementation("pyrlyn.kit:yaml-aot")
            implementation("pyrlyn.kit:semver")
            implementation("pyrlyn.kit:http-ktor")
            implementation("pyrlyn.kit:db-room")
            // Streaming SHA-256 for downloads that do not fit in memory (Rust: sha2), behind ketch.core.hash.Sha256.
            implementation(libs.kotlincrypto.sha2)
        }
    }
}
