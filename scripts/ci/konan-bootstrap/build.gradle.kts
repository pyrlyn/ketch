plugins { kotlin("multiplatform") }

// The host's own target, plus on Linux every Linux target -Pdiffuser.allTargets=true configures.
kotlin {
    if (System.getProperty("os.name").startsWith("Mac")) {
        macosArm64 { binaries.executable { entryPoint = "main" } }
    } else {
        linuxX64 { binaries.executable { entryPoint = "main" } }
        linuxArm64 { binaries.executable { entryPoint = "main" } }
    }
}
