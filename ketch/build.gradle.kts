import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

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
        // The end-to-end suite: the built ketch run as a child, fixture archives, and a stand-in remote on loopback.
        nativeTest.dependencies {
            implementation("pyrlyn.kit:process")
            implementation("pyrlyn.kit:archive")
            implementation("pyrlyn.kit:loopback")
        }
    }
    // Each target's tests run the ketch that target links (Rust: CARGO_BIN_EXE_ketch).
    targets.withType<KotlinNativeTarget>().configureEach {
        val target = this
        tasks.withType<KotlinNativeTest>().matching { it.name == "${target.name}Test" }.configureEach {
            val exe = target.binaries.getExecutable(NativeBuildType.DEBUG)
            dependsOn(exe.linkTaskProvider)
            environment("KETCH_EXE", exe.outputFile.absolutePath)
            environment(
                "TRYCMD_DIR",
                layout.projectDirectory
                    .dir("src/nativeTest/cases")
                    .asFile.absolutePath,
            )
        }
    }
}

// Rust ketch's insta snapshots, kept as they are; SNAPSHOT_UPDATE=always accepts a change.
tasks.withType<KotlinNativeTest>().configureEach {
    environment(
        "SNAPSHOT_DIR",
        layout.projectDirectory
            .dir("src/nativeTest/snapshots")
            .asFile.absolutePath,
    )
    listOf("SNAPSHOT_UPDATE", "INSTA_UPDATE", "CI").forEach { name -> System.getenv(name)?.let { environment(name, it) } }
}
