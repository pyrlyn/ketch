import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

// The `ketch` executable: the command line over :ketch-core (the port of src/).
plugins { id("ketch.kmp") }

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "ketch.cli.main"
            baseName = "ketch"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ketch-core"))
            implementation(project(":ketch-wire"))
            implementation("pyrlyn.kit:cli")
        }
        // Man pages written to disk, and scratch registries for the completion tests.
        nativeMain.dependencies { implementation("pyrlyn.kit:fs") }
    }
}
