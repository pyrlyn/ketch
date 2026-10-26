pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Shared build settings from a sibling checkout of ci-diffuser; CI_DIFFUSER_DIR points elsewhere,
    // e.g. from a worktree outside apps/.
    val ciDiffuser = System.getenv("CI_DIFFUSER_DIR") ?: "../ci-diffuser"
    includeBuild(ciDiffuser)
    includeBuild("$ciDiffuser/settings-plugin")
}

// Repositories and the optional S3/R2 remote build cache, which stays off without its keys.
plugins {
    id("com.github.burrunan.s3-build-cache") version "1.9.10"
    id("ci.diffuser.settings")
}

rootProject.name = "ketch-kotlin"

// TOML, YAML, semver, HTTP and SQLite, shared with rtok-kotlin: a checkout of kotlin-kit.
// KOTLIN_KIT_DIR overrides the path, as CI_DIFFUSER_DIR does above.
includeBuild(System.getenv("KOTLIN_KIT_DIR") ?: "../../packages/kotlin-kit")

include(":ketch-core", ":ketch-wire", ":ketch", ":desktop")
