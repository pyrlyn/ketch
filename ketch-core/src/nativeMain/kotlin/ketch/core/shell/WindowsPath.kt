// A Windows PATH as the registry holds it: entries split the way Windows reads them and compared the way NTFS names
// folders. Pure text, so it is tested on every host.
package ketch.core.shell

import fs.canonicalize
import ketch.core.config.HOST_WINDOWS
import ketch.core.config.env
import ketch.core.text.asciiLowercase
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Whether [dir] already appears as an entry in a Windows PATH string. */
internal fun windowsPathHas(
    path: String,
    dir: Path,
) = windowsPathEntryList(path).any { windowsPathEq(it, dir) }

/** Prepend [dir] if it is missing. Null when it is already present. */
internal fun windowsPathPrepend(
    path: String,
    dir: Path,
): String? {
    if (windowsPathHas(path, dir)) return null
    val text = nativeSpelling(dir)
    return if (path.isEmpty()) text else "$text;$path"
}

/** Drop [dir] if it is present. Null when it was not there. */
internal fun windowsPathRemove(
    path: String,
    dir: Path,
): String? = if (windowsPathHas(path, dir)) windowsPathEntryList(path).filterNot { windowsPathEq(it, dir) }.joinToString(";") else null

/**
 * Split a Windows PATH on `;`, keeping `;` inside quotes as part of one entry.
 *
 * Registry PATH values are sometimes quoted, and a folder name may contain `;` (`C:\weird;name`): a plain split would
 * shatter `"C:\weird;name"` into two entries and miss the bin dir.
 */
internal fun windowsPathEntryList(path: String): List<String> {
    val entries = mutableListOf<String>()
    var start = 0
    var quoted = false
    path.forEachIndexed { i, c ->
        if (c == '"') {
            quoted = !quoted
        } else if (c == ';' && !quoted) {
            if (i > start) entries += path.substring(start, i)
            start = i + 1
        }
    }
    if (start < path.length) entries += path.substring(start)
    return entries
}

private fun windowsPathEq(
    entry: String,
    dir: Path,
): Boolean {
    val expanded = expandedEntry(entry)
    // The same folder spelled another way: `install.ps1` writes the path `Resolve-Path` gives, while the root ketch was
    // started with may be an 8.3 short name (`RUNNER~1`) or go through a link. Folding text cannot see through either;
    // resolving both can, while the folder still exists.
    return windowsPathKey(Path(expanded)) == windowsPathKey(dir) || sameFolder(expanded.trim().trim('"').trim('\''), dir)
}

private fun sameFolder(
    entry: String,
    dir: Path,
): Boolean {
    val a = runCatching { canonicalize(Path(entry)) }.getOrNull()
    val b = runCatching { canonicalize(dir) }.getOrNull()
    return a != null && b != null && windowsPathKey(a) == windowsPathKey(b)
}

/**
 * A folder as Windows compares it: either separator, ASCII case folded, no trailing separator. Registry PATH values are
 * sometimes quoted (`"C:\Program Files\…"`); the quotes go before folding, so a quoted entry is not taken for a
 * different folder and the bin dir is not prepended twice.
 */
internal fun windowsPathKey(p: Path): String =
    p
        .toString()
        .replace('/', '\\')
        .trim()
        .trim('"')
        .trim('\'')
        .trimEnd('\\')
        .asciiLowercase()

/** `%NAME%` replaced the way Windows expands a `REG_EXPAND_SZ` value; a variable [lookup] does not know stays as written. */
internal fun expandPercentVars(
    s: String,
    lookup: (String) -> String?,
): String {
    val out = StringBuilder(s.length)
    var rest = 0
    var open = s.indexOf('%')
    while (open >= 0) {
        out.append(s, rest, open)
        val close = s.indexOf('%', open + 1)
        if (close < 0) {
            rest = open
            break
        }
        val name = s.substring(open + 1, close)
        out.append(name.takeIf { it.isNotEmpty() }?.let(lookup) ?: s.substring(open, close + 1))
        rest = close + 1
        open = s.indexOf('%', rest)
    }
    out.append(s, rest, s.length)
    return out.toString()
}

/**
 * A PATH entry as Windows would use it: `%VAR%` expanded from this process's environment. The registry value is read
 * unexpanded, so an entry written as `%USERPROFILE%\.ketch\bin` must still be recognised as the bin dir.
 */
private fun expandedEntry(entry: String) = expandPercentVars(entry, ::env)

/**
 * The registry kind to write the user Path back as: the one it has, so a `REG_EXPAND_SZ` keeps expanding its `%VAR%`
 * entries, or `ExpandString`, what Windows itself creates, when there is none yet.
 */
internal fun userPathKind(existing: String?): String = existing?.takeIf { it == "String" || it == "ExpandString" } ?: "ExpandString"

/**
 * Entries of a Windows PATH that are a ketch bin dir whose folder is gone: this root's own, or the `bin` of any
 * `.ketch` root, the name every installer defaults to.
 */
internal fun stalePathEntries(
    path: String,
    binDir: Path,
): List<String> =
    windowsPathEntryList(path).filter { entry ->
        val expanded = expandedEntry(entry)
        val key = windowsPathKey(Path(expanded))
        val ketchBin = key == windowsPathKey(binDir) || key.endsWith("\\.ketch\\bin")
        ketchBin && !SystemFileSystem.exists(Path(expanded.trim().trim('"')))
    }

/**
 * [path] spelled the way the host's own tools write it. kotlinx-io joins with `/` on Windows too; what ketch writes
 * into the registry, a profile or cmd's AutoRun should read like what Windows put there.
 */
internal fun nativeSpelling(path: Path): String = if (HOST_WINDOWS) path.toString().replace('/', '\\') else path.toString()
