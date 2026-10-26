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

include(":ketch-core", ":ketch-wire", ":ketch", ":desktop")
