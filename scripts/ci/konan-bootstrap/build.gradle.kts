plugins { kotlin("multiplatform") }

// The host's own target, plus on Linux every Linux target -Pdiffuser.allTargets=true configures.
kotlin {
    val os = System.getProperty("os.name")
    if (os.startsWith("Mac")) {
        macosArm64 { binaries.executable { entryPoint = "main" } }
    } else if (os.startsWith("Windows")) {
        mingwX64 { binaries.executable { entryPoint = "main" } }
    } else {
        linuxX64 { binaries.executable { entryPoint = "main" } }
        linuxArm64 { binaries.executable { entryPoint = "main" } }
    }
}
