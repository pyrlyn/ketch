import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

// A Kotlin Multiplatform module. The native targets (macosArm64, plus Linux with diffuser.allTargets)
// come from ci.diffuser.native in the root build; a module that the desktop app also reads adds jvm().
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val catalog = versionCatalogs.named("libs")

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
    sourceSets {
        commonMain.dependencies {
            implementation(catalog.findLibrary("kotlinx-serialization-json").get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(catalog.findLibrary("kotest-assertions-core").get())
        }
    }
}

// Tests read fixtures by path relative to the module, so every test runs from the module directory.
tasks.withType<Test>().configureEach { workingDir = projectDir }
tasks.withType<KotlinNativeTest>().configureEach { workingDir = projectDir.absolutePath }
