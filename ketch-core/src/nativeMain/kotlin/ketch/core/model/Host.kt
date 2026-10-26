package ketch.core.model

import kotlin.experimental.ExperimentalNativeApi

/** The machine we are running on right now. */
@OptIn(ExperimentalNativeApi::class)
fun TargetSpec.Companion.host(): TargetSpec {
    val os =
        when (Platform.osFamily) {
            OsFamily.MACOSX -> Os.MacOs
            OsFamily.WINDOWS -> Os.Windows
            else -> Os.Linux
        }
    val arch = if (Platform.cpuArchitecture == CpuArchitecture.ARM64) Arch.Aarch64 else Arch.X86_64
    return TargetSpec(os, arch)
}
