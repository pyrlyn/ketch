import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// One Compose desktop app for macOS, Windows and Linux in place of desktop/macos (SwiftUI) and
// desktop/windows (WinUI). It runs the native `ketch` and speaks :ketch-wire with it.
plugins { id("ketch.compose") }

dependencies {
    implementation(project(":ketch-wire"))
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("pyrlyn.fluent:fluent")
    implementation("pyrlyn.glass:glass")
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.markdown.renderer.m3)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

// The native ketch this host links: ServeCore's tests run it and the app's package carries it. A host with no native
// target runs only the scripted tests and packages the app without one.
val hostTarget =
    System.getProperty("os.name").let { os ->
        val allTargets = providers.gradleProperty("diffuser.allTargets").orNull == "true"
        when {
            os.startsWith("Mac") -> "macosArm64"
            os.startsWith("Linux") && allTargets -> if (System.getProperty("os.arch") == "aarch64") "linuxArm64" else "linuxX64"
            os.startsWith("Windows") -> "mingwX64"
            else -> null
        }
    }

// Kotlin/Native names a Windows executable `.exe` and every other one `.kexe`; the app looks for `ketch.exe` or `ketch`.
fun linkedKetch(
    target: String,
    build: String,
) = rootProject.file("ketch/build/bin/$target/${build}Executable/ketch.${if (target == "mingwX64") "exe" else "kexe"}")

// The release ketch in Compose's resource folder for this OS and architecture, which the launcher hands the app as
// compose.application.resources.dir.
val bundledKetch =
    tasks.register<Sync>("bundledKetch") {
        into(layout.buildDirectory.dir("app-resources"))
        hostTarget?.let { target ->
            dependsOn(":ketch:linkReleaseExecutable${target.replaceFirstChar(Char::uppercase)}")
            from(linkedKetch(target, "release")) {
                into(
                    when (target) {
                        "macosArm64" -> "macos-arm64"
                        "linuxArm64" -> "linux-arm64"
                        "mingwX64" -> "windows-x64"
                        else -> "linux-x64"
                    },
                )
                rename { if (target == "mingwX64") "ketch.exe" else "ketch" }
            }
        }
    }

// jpackage copies the app's resources without their modes; the app's (ad hoc) signature does not cover a mode, so the
// bundled ketch is made executable again in the image the packages are built from.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    val images = layout.buildDirectory.dir("compose/binaries")
    doLast {
        images
            .get()
            .asFile
            .walk()
            .filter { it.name == "ketch" && it.parentFile.name == "resources" }
            .forEach { it.setExecutable(true, false) }
    }
}

compose.desktop {
    application {
        mainClass = "ketch.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Ketch"
            // jpackage refuses a macOS version whose first number is 0; release versions are T15's.
            packageVersion = "1.0.0"
            description = "Installs command-line tools and apps straight from GitHub releases"
            copyright = "GPL-3.0-or-later OR royalty-free OR commercial licence"
            vendor = "pyrlyn"
            appResourcesRootDir.set(layout.dir(bundledKetch.map { it.destinationDir }))
            // From suggestRuntimeModules: java.prefs holds the app's settings.
            modules("java.instrument", "java.prefs", "jdk.unsupported")
            macOS {
                bundleID = "io.github.pyrlyn.ketch"
                appCategory = "public.app-category.developer-tools"
                // As the macOS app's Info.plist: the system hands ketch:// links to Desktop's open-URI handler.
                infoPlist {
                    extraKeysRawXml =
                        """
                        |    <key>CFBundleURLTypes</key>
                        |    <array>
                        |        <dict>
                        |            <key>CFBundleURLName</key>
                        |            <string>io.github.pyrlyn.ketch.link</string>
                        |            <key>CFBundleURLSchemes</key>
                        |            <array>
                        |                <string>ketch</string>
                        |            </array>
                        |        </dict>
                        |    </array>
                        """.trimMargin()
                }
            }
            linux {
                packageName = "ketch"
                appCategory = "Development"
                menuGroup = "Development"
                shortcut = true
            }
            windows {
                // Per user, as the login item's HKCU Run value is; the UUID ties every MSI to the one installed app.
                perUserInstall = true
                upgradeUuid = "4779229F-046A-4784-B025-BBA041958528"
                menu = true
                menuGroup = "Ketch"
                shortcut = true
            }
        }
    }
}

tasks.test {
    hostTarget?.let { target ->
        dependsOn(":ketch:linkDebugExecutable${target.replaceFirstChar(Char::uppercase)}")
        environment("KETCH_EXE", linkedKetch(target, "debug").absolutePath)
    }
}

// The brand tokens, copied unchanged from the Rust tree (desktop/design/README.md), read at start.
tasks.processResources { from("design") { include("tokens.json") } }
