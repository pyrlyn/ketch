// The Compose Multiplatform desktop app (JVM).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val catalog = versionCatalogs.named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(catalog.findLibrary("kotlinx-coroutines-core").get())
    testImplementation(kotlin("test"))
    testImplementation(catalog.findLibrary("kotlinx-coroutines-test").get())
}

tasks.withType<Test>().configureEach { workingDir = projectDir }
