// The port of crates/ketch-core: everything ketch does, printing nothing; front ends render its events.
plugins { id("ketch.kmp") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
