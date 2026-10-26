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
            implementation(libs.clikt)
        }
    }
}
