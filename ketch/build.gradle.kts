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
            implementation("pyrlyn.kit:icons")
        }
        // Man pages written to disk, and scratch registries for the completion tests.
        nativeMain.dependencies { implementation("pyrlyn.kit:fs") }
        commonTest.dependencies { implementation("pyrlyn.snapshot:snapshot") }
    }
}

// Rust ketch's insta snapshots, kept as they are; SNAPSHOT_UPDATE=always accepts a change.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment(
        "SNAPSHOT_DIR",
        layout.projectDirectory
            .dir("src/nativeTest/snapshots")
            .asFile.absolutePath,
    )
    listOf("SNAPSHOT_UPDATE", "INSTA_UPDATE", "CI").forEach { name -> System.getenv(name)?.let { environment(name, it) } }
}
