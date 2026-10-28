import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// One Compose desktop app for macOS, Windows and Linux in place of desktop/macos (SwiftUI) and
// desktop/windows (WinUI). It runs the native `ketch` and speaks :ketch-wire with it.
plugins { id("ketch.compose") }

dependencies {
    implementation(project(":ketch-wire"))
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.markdown.renderer.m3)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "ketch.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Ketch"
            packageVersion = "1.0.0"
        }
    }
}

// ServeCore's tests run the native ketch this host links; a host with no native target runs only the scripted ones.
val hostTarget =
    System.getProperty("os.name").let { os ->
        val allTargets = providers.gradleProperty("diffuser.allTargets").orNull == "true"
        when {
            os.startsWith("Mac") -> "macosArm64"
            os.startsWith("Linux") && allTargets -> if (System.getProperty("os.arch") == "aarch64") "linuxArm64" else "linuxX64"
            else -> null
        }
    }

tasks.test {
    hostTarget?.let { target ->
        dependsOn(":ketch:linkDebugExecutable${target.replaceFirstChar(Char::uppercase)}")
        environment("KETCH_EXE", rootProject.file("ketch/build/bin/$target/debugExecutable/ketch.kexe").absolutePath)
    }
}

// The brand tokens, copied unchanged from the Rust tree (desktop/design/README.md), read at start.
tasks.processResources { from("design") { include("tokens.json") } }
