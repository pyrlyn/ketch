// `ketch://` links: the one place a URL some web page or registry listing handed a front end becomes a package name.
//
// A link can only open a package's page. It never names an install, an upgrade or an uninstall, even to ask for
// confirmation (creator decision, Rust ketch docs/research-desktop-platforms.md, open decision 10), so the grammar has
// exactly one shape — `ketch://package/<name>` — and everything else is refused rather than interpreted. Every byte
// of the link is untrusted: it came from outside the app and may be shown to the user and used to look up a path, so
// the name passes the same guard a manifest's package name does.
package ketch.core.link

import ketch.core.error.KetchError
import ketch.core.model.normalizeName
import ketch.core.model.usableFileName
import ketch.core.text.codePoints
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.escapeDebug
import ketch.core.text.isAsciiAlphanumeric

/** The URL scheme front ends register with the operating system. */
const val SCHEME = "ketch"

/** The only action a link may name. */
private const val ACTION = "package"

/** Longer than any real package link by a wide margin; bounding it keeps a hostile link from being echoed into an error dialog at any length. */
internal const val MAX_LEN = 512

/** Package names are short; the registry's longest is far below this. */
internal const val MAX_NAME = 100

/** How much of a refused link its error quotes. */
private const val QUOTED = 80

/** A query or fragment would be a second channel for instructions, and userinfo, ports, escapes and backslashes are how a URL says one thing to a parser and another to a person. */
private const val NOT_IN_A_LINK = "?#@:%\\"

/**
 * The package a `ketch://package/<name>` link names, ready to look up.
 *
 * This only validates the shape and the name. It does not check that the package exists: that is the caller's
 * lookup, which keeps this free of the network and of the install tree.
 */
fun packageName(raw: String): String {
    if (raw.encodeToByteArray().size > MAX_LEN) throw KetchError.Msg("ketch link is longer than $MAX_LEN bytes")
    // ASCII graphic only: whitespace and control characters hide text, and non-ASCII letters would let a lookalike
    // name pass for another package.
    if (!raw.all { it in '!'..'~' }) refuse(raw, "contains characters a link may not carry")
    val sep = raw.indexOf("://")
    if (sep < 0) refuse(raw, "is not a URL")
    if (!raw.substring(0, sep).eqIgnoreAsciiCase(SCHEME)) refuse(raw, "is not a ketch link")
    val rest = raw.substring(sep + "://".length)
    if (rest.any { it in NOT_IN_A_LINK }) refuse(raw, "carries more than a package name")
    val parts = rest.split('/')
    if (parts.size < 2) refuse(raw, "names no package")
    val (action, name) = parts
    if (!action.eqIgnoreAsciiCase(ACTION)) {
        throw KetchError.Msg("ketch links open a package page only; `${escapeDebug(action)}` is not an action they support")
    }
    // One optional trailing slash, nothing deeper: `package/a/b` is not a package called `a`.
    val tail = parts.drop(2)
    if (tail.size > 1 || tail.any { it.isNotEmpty() }) refuse(raw, "names more than one package")
    return usableName(name)
}

private fun usableName(name: String): String {
    val shaped =
        name.length <= MAX_NAME &&
            name.firstOrNull()?.isAsciiAlphanumeric() == true &&
            name.all { it.isAsciiAlphanumeric() || it in "-_.+" }
    if (!shaped) throw KetchError.Msg("`${escapeDebug(name)}` is not a package name")
    usableFileName("package name", name)
    return normalizeName(name)
}

/** Escaped so a hostile link cannot rewrite the line it is reported on. */
private fun refuse(
    raw: String,
    why: String,
): Nothing = throw KetchError.Msg("ketch link `${escapeDebug(codePoints(raw).take(QUOTED).joinToString(""))}` $why")
