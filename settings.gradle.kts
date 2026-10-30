pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Shared builds live in the workspace's packages/. Every ancestor is searched, so the build works
    // from the main checkout and from a worktree nested deeper (e.g. .rtok/worktrees/); an env variable
    // points elsewhere. Defined here because pluginManagement cannot see the rest of the script.
    fun sharedBuild(name: String, env: String): String =
        System.getenv(env)?.let { settingsDir.resolve(it).path }
            ?: generateSequence(settingsDir.parentFile) { it.parentFile }
                .map { it.resolve("packages/$name") }
                .firstOrNull { it.isDirectory }
                ?.path
            ?: error("packages/$name not found above $settingsDir; set $env")

    // Shared build settings (Kotlin/Native targets, ktlint, detekt, repositories, build cache).
    val sharedSettings = sharedBuild("shared-settings", "SHARED_SETTINGS_DIR")
    includeBuild(sharedSettings)
    includeBuild("$sharedSettings/settings-plugin")
    extra["kotlinAdwaitaDir"] = sharedBuild("kotlin-adwaita", "KOTLIN_ADWAITA_DIR")
    extra["kotlinFluentDir"] = sharedBuild("kotlin-fluent", "KOTLIN_FLUENT_DIR")
    extra["kotlinGlassDir"] = sharedBuild("kotlin-glass", "KOTLIN_GLASS_DIR")
    extra["kotlinKitDir"] = sharedBuild("kotlin-kit", "KOTLIN_KIT_DIR")
    extra["kotlinSnapshotDir"] = sharedBuild("kotlin-snapshot", "KOTLIN_SNAPSHOT_DIR")
}

// Repositories and the optional S3/R2 remote build cache, which stays off without its keys.
plugins {
    id("com.github.burrunan.s3-build-cache") version "1.9.10"
    id("shared.settings")
}

rootProject.name = "ketch-kotlin"

// The macOS look's generic components: a checkout of kotlin-glass.
includeBuild(extra["kotlinGlassDir"] as String)

// The Windows look's generic components: a checkout of kotlin-fluent.
includeBuild(extra["kotlinAdwaitaDir"] as String)
includeBuild(extra["kotlinFluentDir"] as String)

// TOML, YAML, semver, HTTP and SQLite, shared with rtok-kotlin: a checkout of kotlin-kit.
includeBuild(extra["kotlinKitDir"] as String)

// insta-format snapshot assertions for the ported snapshot tests.
includeBuild(extra["kotlinSnapshotDir"] as String)

include(":ketch-core", ":ketch-wire", ":ketch", ":desktop")
