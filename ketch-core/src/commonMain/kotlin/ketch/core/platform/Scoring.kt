package ketch.core.platform

import ketch.core.binchoice.asciiLowercase
import ketch.core.model.Arch
import ketch.core.model.Os

/*
 * Shared asset-name scoring helpers. Common code, so every host's tests exercise token matching, rejection rules
 * and container tie-breaks without a platform backend.
 */

private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Does [needle] appear in [haystack] as a whole token? Plain `contains` is not usable: `darwin` contains `win`,
 * `install` contains `all`, and either would misroute an asset to the wrong platform.
 */
fun tokenAt(
    haystack: String,
    needle: String,
): Boolean {
    var start = haystack.indexOf(needle)
    while (start >= 0) {
        val end = start + needle.length
        val left = start == 0 || !haystack[start - 1].isAsciiAlphanumeric()
        val right = end == haystack.length || !haystack[end].isAsciiAlphanumeric()
        if (left && right) return true
        // Rust's match_indices does not overlap matches.
        start = haystack.indexOf(needle, end.coerceAtLeast(start + 1))
    }
    return false
}

/** First token from [tokens] that appears whole in [haystack]. */
fun findToken(
    haystack: String,
    tokens: List<String>,
): String? = tokens.firstOrNull { tokenAt(haystack, it) }

/** True when the asset name names an OS ketch does not support. */
fun namesForeignOs(lower: String) = findToken(lower, FOREIGN_OS_TOKENS) != null

/**
 * Bonus and label for the container format, read off the file name. The spread is deliberately small: it only
 * breaks ties between assets that already agree on OS and architecture.
 */
fun containerBonus(lower: String): Pair<Int, String> {
    for ((suffix, bonus, label) in KNOWN_CONTAINERS) {
        if (lower.endsWith(suffix)) return bonus to label
    }
    // No recognised container: most likely the bare executable.
    return RAW_BONUS to "raw"
}

private const val RAW_BONUS = 4

private val KNOWN_CONTAINERS =
    listOf(
        Triple(".tar.gz", 8, "tar.gz"),
        Triple(".tgz", 8, "tar.gz"),
        Triple(".tar.xz", 7, "tar.xz"),
        Triple(".txz", 7, "tar.xz"),
        Triple(".tar.bz2", 5, "tar.bz2"),
        Triple(".tar", 6, "tar"),
        Triple(".zip", 6, "zip"),
        Triple(".exe", 7, "exe"),
        Triple(".gz", 5, "gz"),
        Triple(".dmg", 3, "dmg"),
        Triple(".pkg", 2, "pkg"),
    )

/**
 * True when the lower-cased asset name is never installable. [extraTokens] carries platform-specific by-product
 * markers (macOS debug symbols, say) that reject an asset when they appear as whole tokens.
 */
fun isRejected(
    lower: String,
    extraTokens: List<String>,
): Boolean =
    isSidecar(lower) ||
        NON_BINARY_TOKENS.any { tokenAt(lower, it) } ||
        REJECTED_EXTENSIONS.any { lower.endsWith(it) } ||
        extraTokens.any { tokenAt(lower, it) }

/**
 * A raw binary asset lands under the asset's own file name — `jq-macos-arm64` — which is not what anyone wants on
 * PATH. Rename to the package name only when the discovered name plainly carries build metadata.
 */
fun looksLikeBuildArtifact(name: String): Boolean {
    val lower = name.asciiLowercase()
    val platformToken =
        listOf(Os.MacOs.tokens, Os.Linux.tokens, Os.Windows.tokens, Arch.Aarch64.tokens, Arch.X86_64.tokens, Arch.Universal.tokens)
            .flatten()
            .any { tokenAt(lower, it) }
    return platformToken || hasVersionRun(lower)
}

/** True for names carrying something like `1.2` or `v3`. */
private fun hasVersionRun(lower: String): Boolean = VERSION_RUN.containsMatchIn(lower)

private val VERSION_RUN = Regex("""[0-9]\.[0-9]|v[0-9]""")

/** Extra tokens that mark a macOS asset as a build by-product. */
private val BYPRODUCT_TOKENS = listOf("dsym", "debuginfo", "symbols")

private const val OS_NAMED = 50
private const val OS_UNNAMED = 15
private const val ARCH_NATIVE = 40
private const val ARCH_UNIVERSAL = 35
private const val ARCH_EMULATED = 10
private const val ARCH_UNNAMED = 18
private const val LIBC_NATIVE = 12
private const val LIBC_MUSL_ON_GNU = 6

/** The architecture an asset provides, whether only under emulation, and its share of the score. */
private data class ArchFit(
    val arch: Arch,
    val emulated: Boolean,
    val points: Int,
)

/** How one OS reads an asset name's architecture: the order of the checks is Rust ketch's, per backend. */
private data class ArchRules(
    /** Whether a universal (fat) build counts, macOS only. */
    val universal: Boolean,
    /** Whether an x86_64 build may run on an aarch64 host under emulation. */
    val emulates: Boolean,
    /** What an asset that names no architecture provides; null means the host's. */
    val unnamed: Arch?,
    /** The reason an emulated pick shows. */
    val emulatedLabel: String,
)

private val MACOS_ARCH = ArchRules(universal = true, emulates = true, unnamed = Arch.Universal, emulatedLabel = "x86_64 under Rosetta")
private val LINUX_ARCH = ArchRules(universal = false, emulates = true, unnamed = null, emulatedLabel = "x86_64 under emulation")
private val WINDOWS_ARCH = ArchRules(universal = false, emulates = false, unnamed = null, emulatedLabel = "")

/** The asset's architecture fit for [hostArch], or null when it names a real architecture this machine cannot run. */
private fun archFit(
    lower: String,
    hostArch: Arch,
    rules: ArchRules,
): ArchFit? =
    when {
        findToken(lower, hostArch.tokens) != null -> {
            ArchFit(hostArch, false, ARCH_NATIVE)
        }

        rules.universal && findToken(lower, Arch.Universal.tokens) != null -> {
            ArchFit(Arch.Universal, false, ARCH_UNIVERSAL)
        }

        rules.emulates && hostArch == Arch.Aarch64 && findToken(lower, Arch.X86_64.tokens) != null -> {
            ArchFit(Arch.X86_64, true, ARCH_EMULATED)
        }

        namesAnyArch(lower) -> {
            null
        }

        else -> {
            ArchFit(rules.unnamed ?: hostArch, false, ARCH_UNNAMED)
        }
    }

private fun namesAnyArch(lower: String) = findToken(lower, Arch.Aarch64.tokens) != null || findToken(lower, Arch.X86_64.tokens) != null

private fun namesOs(
    lower: String,
    os: Os,
) = findToken(lower, os.tokens) != null

/** Rejections every backend shares: empty, sidecar or non-binary, a by-product, an unsupported OS. */
private fun refusedEverywhere(
    lower: String,
    byproducts: List<String>,
): Boolean = lower.isEmpty() || isRejected(lower, byproducts) || namesForeignOs(lower)

/** Score a release asset name for macOS without constructing a platform backend. */
fun scoreMacosAsset(
    assetName: String,
    hostArch: Arch,
    allowEmulation: Boolean,
): AssetScore? {
    val lower = assetName.trim().asciiLowercase()
    // Anything that names a foreign OS is not ours, whatever else it says.
    val refused = refusedEverywhere(lower, BYPRODUCT_TOKENS) || lower.endsWith(".exe") || namesOs(lower, Os.Linux) || namesOs(lower, Os.Windows)
    if (refused) return null
    val reason = mutableListOf<String>()
    // No OS in the name at all: single-platform projects do this, so it stays a candidate but loses to anything explicit.
    val osToken = findToken(lower, Os.MacOs.tokens)
    osToken?.let { reason += it }
    val fit = archFit(lower, hostArch, MACOS_ARCH)?.takeIf { !it.emulated || allowEmulation }
    return fit?.let { finish(lower, if (osToken != null) OS_NAMED else OS_UNNAMED, it, reason, MACOS_ARCH) }
}

/**
 * Score a release asset name for Linux without constructing a platform backend. [muslHost] is whether this
 * machine's libc is musl: a glibc (`gnu`) asset is refused there because it will not load; a musl asset on a
 * glibc host is accepted but ranks below a native glibc build.
 */
fun scoreLinuxAsset(
    assetName: String,
    hostArch: Arch,
    allowEmulation: Boolean,
    muslHost: Boolean,
): AssetScore? {
    val lower = assetName.trim().asciiLowercase()
    val hasLinux = tokenAt(lower, "linux")
    val hasMusl = tokenAt(lower, "musl")
    val hasGnu = tokenAt(lower, "gnu")
    // A glibc asset will not load on a musl host.
    val wrongLibc = muslHost && hasGnu && !hasMusl
    if (wrongLibc || refusedOnLinux(lower)) return null
    val reason = mutableListOf<String>()
    if (hasLinux) reason += "linux"
    val osPoints = if (hasLinux || hasMusl || hasGnu) OS_NAMED else OS_UNNAMED
    val libc = libcFit(muslHost, hasMusl, hasGnu)
    libc?.let { reason += it.second }
    val fit = archFit(lower, hostArch, LINUX_ARCH)?.takeIf { !it.emulated || allowEmulation }
    return fit?.let { finish(lower, osPoints + (libc?.first ?: 0), it, reason, LINUX_ARCH) }
}

/** Rejections of a Linux asset that do not depend on the host's libc. */
private fun refusedOnLinux(lower: String): Boolean =
    refusedEverywhere(lower, listOf("debuginfo", "dbg")) || lower.endsWith(".exe") ||
        namesOs(lower, Os.MacOs) || namesOs(lower, Os.Windows)

/** The libc bonus and its reason: a native libc build, or musl on a glibc host, which runs everywhere. */
private fun libcFit(
    muslHost: Boolean,
    hasMusl: Boolean,
    hasGnu: Boolean,
): Pair<Int, String>? =
    when {
        muslHost && hasMusl -> LIBC_NATIVE to "musl"
        !muslHost && !hasMusl && hasGnu -> LIBC_NATIVE to "gnu"
        !muslHost && hasMusl -> LIBC_MUSL_ON_GNU to "musl"
        else -> null
    }

/**
 * Score a release asset name for Windows without constructing a platform backend. `gnu` is not treated as
 * foreign: mingw triples (`pc-windows-gnu`) are Windows. `linux` and `musl` are.
 */
fun scoreWindowsAsset(
    assetName: String,
    hostArch: Arch,
): AssetScore? {
    val lower = assetName.trim().asciiLowercase()
    val refused = refusedEverywhere(lower, emptyList()) || namesOs(lower, Os.MacOs) || tokenAt(lower, "linux") || tokenAt(lower, "musl")
    if (refused) return null
    val reason = mutableListOf<String>()
    val osToken = findToken(lower, Os.Windows.tokens)
    osToken?.let { reason += it }
    return archFit(lower, hostArch, WINDOWS_ARCH)?.let { finish(lower, if (osToken != null) OS_NAMED else OS_UNNAMED, it, reason, WINDOWS_ARCH) }
}

private fun finish(
    lower: String,
    osPoints: Int,
    fit: ArchFit,
    reason: MutableList<String>,
    rules: ArchRules,
): AssetScore {
    reason += if (fit.emulated) rules.emulatedLabel else fit.arch.toString()
    val (bonus, container) = containerBonus(lower)
    reason += container
    return AssetScore(osPoints + fit.points + bonus, fit.arch, fit.emulated, reason.joinToString(" / "))
}
