// The checksum files releases publish beside their assets: per-asset `.sha256` sidecars and aggregate lists.
package ketch.core.source

import ketch.core.hash.isSha256Hex
import ketch.core.platform.isSidecar
import ketch.core.text.asciiLowercase

/**
 * Container extensions an aggregate checksum list never has. It is a text file; anything packaged or signed is a
 * different artefact that happens to mention checksums in its name.
 */
private val NOT_A_CHECKSUM_LIST = listOf(".tar", ".gz", ".tgz", ".xz", ".txz", ".bz2", ".zip", ".dmg", ".pkg", ".exe", ".jar", ".7z")

private const val SIDECAR_EXT = ".sha256"

private val WHITESPACE = Regex("\\s+")

private fun words(text: String) = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

/** The `sha256:<hex>` digest the API reports, as lowercase hex. */
internal fun parseDigest(raw: String): String? {
    val hex = raw.removePrefix("sha256:").takeIf { it.length < raw.length }?.trim() ?: return null
    return hex.takeIf(::isSha256Hex)?.asciiLowercase()
}

/**
 * Names that hold checksums for several assets at once.
 *
 * Matching on the name alone is unavoidable (nothing else distinguishes the file before it is downloaded), so the
 * negative half matters as much as the positive one. `checksum-verifier-darwin-arm64.tar.gz` and `checksums.txt.sig`
 * both contain "checksum" and neither is a list of them; fetching either as text costs a whole asset transfer and
 * yields nothing.
 */
internal fun isAggregateChecksumFile(name: String): Boolean {
    val lower = name.asciiLowercase()
    val claimsChecksums = "sha256sum" in lower || "sha256_sums" in lower || "checksum" in lower || lower == "sums.txt"
    return claimsChecksums && !isSidecar(lower) && NOT_A_CHECKSUM_LIST.none { lower.endsWith(it) }
}

/**
 * The asset a `.sha256` sidecar carries the checksum for. The extension is matched without regard to case: releases
 * publish `Tool.zip.SHA256` as readily as the lowercase spelling, and a sidecar that goes unrecognised is a published
 * checksum ketch silently never checks.
 */
internal fun sidecarTarget(name: String): String? = if (name.asciiLowercase().endsWith(SIDECAR_EXT)) name.dropLast(SIDECAR_EXT.length) else null

/**
 * Parse the `sha256sum` output format: `<hex><space><space|*><name>`. Names may carry a leading `./` or a directory
 * prefix, so only the file name is kept: that is what the asset list is keyed by. A name listed twice with different
 * hashes is dropped rather than either one trusted.
 */
internal fun parseChecksumFile(body: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val conflicts = mutableSetOf<String>()
    for ((hex, name) in body
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull(::entry)) {
        val existing = out[name]
        if (existing == null) {
            out[name] = hex
        } else if (existing != hex) {
            conflicts += name
        }
    }
    conflicts.forEach(out::remove)
    return out
}

/** One `<hex> <name>` line as lowercase hex and the bare file name; null for anything else. */
private fun entry(line: String): Pair<String, String>? {
    val parts = words(line)
    val hex = parts.getOrNull(0)?.takeIf(::isSha256Hex)?.asciiLowercase()
    val name =
        parts
            .getOrNull(1)
            ?.trimStart('*')
            ?.let(::stripDotSlashes)
            ?.substringAfterLast('/')
    return if (hex != null && name != null) hex to name else null
}

/** Rust `trim_start_matches("./")`: every leading `./`, not just one. */
private fun stripDotSlashes(name: String): String {
    var rest = name
    while (rest.startsWith("./")) rest = rest.substring(2)
    return rest
}

/**
 * The hash a `.sha256` sidecar carries for [target]. Prefer a line that names the asset. A one-entry file (or a lone
 * hex) is the usual sidecar shape; several names with no match are ignored rather than picking an alphabetic first line.
 */
internal fun sidecarHex(
    body: String,
    target: String,
): String? {
    val parsed = parseChecksumFile(body)
    return parsed[target] ?: parsed.values.singleOrNull() ?: words(body).firstOrNull()?.takeIf(::isSha256Hex)?.asciiLowercase()
}
