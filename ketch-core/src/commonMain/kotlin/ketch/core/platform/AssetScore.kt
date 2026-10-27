package ketch.core.platform

import ketch.core.model.Arch
import ketch.core.text.BYTE_ORDER
import ketch.core.text.asciiLowercase

/** How well a release asset suits this machine. */
data class AssetScore(
    /** Higher wins. Only compared between assets of the same release. */
    val score: Int,
    /** Architecture this asset actually provides. */
    val arch: Arch,
    /** True when it runs only under emulation (x86_64 on Apple Silicon). */
    val emulated: Boolean,
    /** Short explanation, shown with `--verbose` and in `ketch info`. */
    val reason: String,
)

/** Suffixes that mean "this is not a program". Shared by every platform: sidecars look the same everywhere. */
val SIDECAR_SUFFIXES =
    listOf(
        ".sha256",
        ".sha512",
        ".sha1",
        ".md5",
        ".asc",
        ".sig",
        ".sigstore",
        ".pem",
        ".crt",
        ".sbom",
        ".sbom.json",
        ".spdx.json",
        ".intoto.jsonl",
        ".pubkey",
        ".minisig",
        ".cert",
    )

/** Tokens that mark a file as source code or metadata, not a build. */
val NON_BINARY_TOKENS =
    listOf(
        "checksum",
        "checksums",
        "sha256sums",
        "sha512sums",
        "source-code",
        "sources",
        "src.tar",
        "src",
        "vendor",
        "manifest",
        "provenance",
        "attestation",
        "changelog",
        "release-notes",
    )

/**
 * Tokens for operating systems ketch does not support. An asset naming one of these is never installable on
 * macOS, Linux or Windows — even when it also carries a recognised architecture token.
 */
val FOREIGN_OS_TOKENS = listOf("freebsd", "netbsd", "openbsd", "plan9", "dragonfly")

/** Extensions that never contain a runnable macOS/Linux payload. */
val REJECTED_EXTENSIONS =
    listOf(
        ".txt",
        ".md",
        ".json",
        ".yaml",
        ".yml",
        ".xml",
        ".csv",
        ".log",
        ".deb",
        ".rpm",
        ".apk",
        ".msi",
        ".appimage",
        ".snap",
        ".flatpak",
        ".nupkg",
        ".jar",
        ".war",
        ".whl",
        ".gem",
    )

/** True when [name] ends with any known sidecar suffix. */
fun isSidecar(name: String): Boolean {
    val lower = name.asciiLowercase()
    return SIDECAR_SUFFIXES.any { lower.endsWith(it) }
}

/** File name compared as a package binary: a trailing `.exe` folded away, ASCII case folded. */
private fun executableNameKey(name: String): String = name.asciiLowercase().removeSuffix(".exe")

/**
 * Order discovered executables (file names) so the package's own binary comes first. A plain sort is not that
 * order: `rtok` sorts before `rtok-hook`, but `rtok-hook.exe` sorts before `rtok.exe`. The binary whose name
 * equals the package name wins; everything else keeps byte order, so the result does not depend on listing order.
 */
fun orderDiscoveredExecutables(
    found: List<String>,
    pkg: String,
): List<String> {
    val want = executableNameKey(pkg)
    val name = { path: String -> path.substringAfterLast('/') }
    return found.sortedWith(compareBy<String> { executableNameKey(name(it)) != want }.then(BYTE_ORDER))
}
