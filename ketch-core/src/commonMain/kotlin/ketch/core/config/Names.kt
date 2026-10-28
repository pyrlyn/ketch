package ketch.core.config

import ketch.core.error.KetchError
import ketch.core.text.asciiUppercase
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.isAsciiAlphanumeric
import ketch.core.text.rustTrim
import kotlinx.io.files.Path

/** The upstream repository ketch updates itself from. */
const val SELF_REPO = "pyrlyn/ketch"

/** The package registry ketch resolves names against: a GitHub repository with one folder per package. */
const val REGISTRY_REPO = "pyrlyn/ketch-registry"

/**
 * Repositories that moved from the `listepo` account to the `pyrlyn` organization, old name first. Copies
 * installed before the move still carry the old name in `config.toml`, the state file and lockfiles; GitHub's
 * redirect for it is not something a release may depend on (a token scoped to the new owner gets a 403 from it),
 * so the old name is read and the new one is used.
 */
val RENAMED_REPOS: List<Pair<String, String>> =
    listOf("listepo/ketch" to SELF_REPO, "listepo/ketch-registry" to REGISTRY_REPO)

/** The current name of [repo] (`owner/repo`) when it is one that moved. GitHub names ignore case, so this does too. */
fun currentRepo(repo: String): String = RENAMED_REPOS.firstOrNull { it.first.eqIgnoreAsciiCase(repo) }?.second ?: repo

/** True on Windows, where file names have rules of their own. */
expect val HOST_WINDOWS: Boolean

/** kit's `joinPath` (the host's separator between parts, as `PathBuf::join`), for common code that cannot see kit. */
internal expect fun joinedPath(
    base: Path,
    vararg parts: String,
): Path

/**
 * Accept only `owner/repo`, since it is about to become a URL. A `github:` prefix is tolerated because that is
 * how the same repository is written everywhere else in ketch; the stored form drops it.
 */
fun validateRepo(
    what: String,
    raw: String,
): String {
    var repo = raw.rustTrim()
    while (repo.startsWith("github:")) repo = repo.removePrefix("github:")
    val parts = repo.split('/')
    // `.` is not a traversal, but it is not an owner or a repository either: `a/.` becomes a URL the path parser
    // rewrites into a different endpoint than the one that was named.
    val shaped = parts.size == 2 && parts.all { it.isNotEmpty() && it != "." && it != ".." }
    val printable = repo.all { it.isAsciiAlphanumeric() || it in "-_./" }
    if (shaped && printable && ".." !in repo) return repo
    throw KetchError.Config("$what `$raw` is not a GitHub repository; expected `owner/repo`")
}

/**
 * Make a string safe to use as one path component. Version tags can legally contain `/` (`release/1.2`), which
 * would otherwise escape the store.
 */
fun sanitizeComponent(raw: String): String = sanitizeComponentFor(raw, HOST_WINDOWS)

/** [sanitizeComponent] with the Windows rules switched on or off, so both are tested on every host. */
internal fun sanitizeComponentFor(
    raw: String,
    windows: Boolean,
): String {
    val cleaned =
        buildString(raw.length) {
            for (c in raw) {
                val unsafe = c in "/\\:" || c.isISOControl() || c in BIDI || (windows && c in WINDOWS_FORBIDDEN)
                append(if (unsafe) '-' else c)
            }
        }
    val trimmed = cleaned.trim('.', ' ', '-')
    return when {
        trimmed.isEmpty() -> "unknown"

        // `nul` or `con.1` would open a device rather than name a directory.
        windows && isDosDeviceName(trimmed) -> "_$trimmed"

        else -> trimmed
    }
}

/** The bidi overrides and isolates, which make a name read differently from what it is. */
private val BIDI = ('‪'..'‮') + ('⁦'..'⁩')

/** Characters Windows refuses in a file name, besides the separators and `:` every guard here rejects. */
private const val WINDOWS_FORBIDDEN = "<>\"|?*"

/**
 * True when Windows could not use [name] as one path component as written: a DOS device name opens the device
 * instead of a file, and a trailing dot or space is silently dropped, so `foo.` lands on `foo`. Pure so it is
 * tested on every host; only Windows applies it.
 */
internal fun windowsUnusableComponent(name: String): Boolean =
    name.endsWith('.') ||
        name.endsWith(' ') ||
        name.any { it in WINDOWS_FORBIDDEN || it == '\\' || it == ':' || it.isISOControl() } ||
        isDosDeviceName(name)

private val DEVICES = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$")
private val PORT_NUMBERS = ('0'..'9').map { it.toString() }.toSet() + setOf("¹", "²", "³")

/**
 * `CON`, `PRN`, `AUX`, `NUL`, `CONIN$`, `CONOUT$`, `COM0`–`COM9` and `LPT0`–`LPT9` (superscript 1–3 too), in any
 * case and with any extension: Windows reads the stem before the first dot, trailing spaces dropped.
 */
private fun isDosDeviceName(name: String): Boolean {
    val upper = name.substringBefore('.').trimEnd(' ').asciiUppercase()
    val port = upper.removePrefix("COM").takeIf { it != upper } ?: upper.removePrefix("LPT").takeIf { it != upper }
    return upper in DEVICES || port in PORT_NUMBERS
}
