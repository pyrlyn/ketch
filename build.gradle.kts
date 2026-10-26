// Targets, release-size options, ktlint and detekt for every Kotlin module come from ci-diffuser;
// the Kotlin and Compose plugins themselves are on the classpath through buildSrc.
plugins {
    id("ci.diffuser.native")
}

// Tests never see the real home: every native test run gets a per-module temp HOME and KETCH_HOME under build/.
subprojects {
    val testHome = layout.buildDirectory.dir("test-home")
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
        val h = testHome.get().asFile
        doFirst { h.resolve(".ketch").mkdirs() }
        environment("HOME", h.absolutePath)
        environment("KETCH_HOME", h.resolve(".ketch").absolutePath)
    }
}
