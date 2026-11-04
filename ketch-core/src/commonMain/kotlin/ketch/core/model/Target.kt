package ketch.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** An operating system ketch installs for, with the tokens that name it in asset file names. */
@Serializable
enum class Os(
    private val display: String,
    /** File name tokens that indicate this OS. Order is not significant. */
    val tokens: List<String>,
) {
    @SerialName("macos")
    MacOs("macos", listOf("darwin", "macos", "mac", "osx", "apple", "macosx")),

    @SerialName("linux")
    Linux("linux", listOf("linux", "gnu", "musl")),

    @SerialName("windows")
    Windows("windows", listOf("windows", "win32", "win64", "win", "msvc")),
    ;

    override fun toString() = display
}

/** A CPU architecture, with the tokens that name it in asset file names. */
@Serializable
enum class Arch(
    private val display: String,
    val tokens: List<String>,
) {
    @SerialName("aarch64")
    Aarch64("aarch64", listOf("aarch64", "arm64", "armv8", "apple-silicon", "silicon", "m1")),

    @SerialName("x86_64")
    X86_64("x86_64", listOf("x86_64", "x8664", "amd64", "x64", "intel", "64bit")),

    /** A fat binary that runs on any architecture of the host OS. */
    @SerialName("universal")
    Universal("universal", listOf("universal", "universal2", "fat", "all")),
    ;

    override fun toString() = display
}

/** The OS and architecture an asset is picked for. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class TargetSpec(
    val os: Os,
    val arch: Arch,
) {
    override fun toString() = "$os-$arch"

    companion object {
        /**
         * The inverse of [toString], for values that arrive as text — a lockfile entry, a command line.
         * Throws [IllegalArgumentException] with the message Rust ketch gives.
         */
        fun parse(text: String): TargetSpec {
            val dash = text.indexOf('-')
            val os = if (dash < 0) null else Os.entries.firstOrNull { it.toString() == text.substring(0, dash) }
            val arch = if (dash < 0) null else Arch.entries.firstOrNull { it.toString() == text.substring(dash + 1) }
            require(os != null && arch != null) { "`$text` is not a target; expected `<os>-<arch>`" }
            return TargetSpec(os, arch)
        }
    }
}
