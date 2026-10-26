import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// One Compose desktop app for macOS, Windows and Linux in place of desktop/macos (SwiftUI) and
// desktop/windows (WinUI). It runs the native `ketch` and speaks :ketch-wire with it.
plugins { id("ketch.compose") }

dependencies {
    implementation(project(":ketch-wire"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
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
