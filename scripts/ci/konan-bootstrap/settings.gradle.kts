// A throwaway Kotlin/Native build for CI: linking it makes Kotlin/Native download its distribution, clang and
// sysroots into ~/.konan, which the main build (kotlin-kit's per-target conan/cinterop tasks) reads while it is still
// configuring. Same idea as pyrlyn/rtok's scripts/ci/konan-bootstrap.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    // The Kotlin pin from gradle/libs.versions.toml, passed in by the workflow, so the bootstrap fetches the same toolchain.
    plugins { kotlin("multiplatform") version (System.getenv("KOTLIN_VERSION") ?: error("set KOTLIN_VERSION")) }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "konan-bootstrap"
