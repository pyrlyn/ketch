package ketch.core.model

import ketch.core.config.HOST_WINDOWS
import ketch.core.error.KetchError
import ketch.core.text.BYTE_ORDER
import ketch.core.text.asciiLowercase
import ketch.core.text.codePoints
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.files.Path

/** Minimal glob: `*` matches any run, `?` one character. Ignores ASCII case: release names are not consistent about it. */
fun globMatch(
    pattern: String,
    text: String,
): Boolean {
    val p = codePoints(pattern.asciiLowercase())
    val t = codePoints(text.asciiLowercase())
    // Iterative backtracking stays linear in the common case and avoids the exponential blowup a recursive
    // matcher has on `*a*a*a*`.
    var pi = 0
    var ti = 0
    var star = -1
    var mark = 0
    while (ti < t.size) {
        when {
            pi < p.size && (p[pi] == "?" || p[pi] == t[ti]) -> {
                pi += 1
                ti += 1
            }

            pi < p.size && p[pi] == "*" -> {
                star = pi
                mark = ti
                pi += 1
            }

            star >= 0 -> {
                pi = star + 1
                mark += 1
                ti = mark
            }

            else -> {
                return false
            }
        }
    }
    while (pi < p.size && p[pi] == "*") pi += 1
    return pi == p.size
}

/**
 * The payload file to link when one `bin` glob matched several: the one whose stem is the link [name], so `rtok*`
 * links `rtok.exe` and never the `rtok-hook.exe` beside it. When several share that stem the sorted-first wins.
 * With several matches and none named like the link the manifest is ambiguous and this refuses, listing the
 * candidates sorted and relative to [root] so the message reads the same on every OS: taking the first match
 * would link whatever the directory lists first, and NTFS lists `rtok-hook.exe` ahead of `rtok.exe`.
 */
fun globPreferred(
    root: Path,
    pattern: String,
    matched: List<Path>,
    name: String?,
): Path? {
    val sorted = matched.sortedWith(PATH_ORDER)
    val want = name?.let { fileStem(Path(it).name) }
    val named = if (want == null) null else sorted.firstOrNull { fileStem(it.name)?.eqIgnoreAsciiCase(want) == true }
    if (sorted.size <= 1 || named != null) return named ?: sorted.firstOrNull()
    val rootParts = rustComponents(root)
    val listed =
        sorted.joinToString("\n") { p ->
            val parts = rustComponents(p)
            val rel = if (parts.take(rootParts.size) == rootParts) parts.drop(rootParts.size) else parts
            "  ${rel.joinToString("/")}"
        }
    throw KetchError.Msg(
        "the manifest's `bin` path `$pattern` matches ${sorted.size} files and none is named like the link:\n" +
            "$listed\n" +
            "set the entry's `name` to the one to link, or narrow its `path` to a single file",
    )
}

/**
 * Rust's `Path::components`: empty and `.` parts dropped, the root its own `""` part. On Windows both `/` and `\` part
 * names, and a prefix leads: a drive as `C:` (Rust compares drive letters folded), or a UNC or verbatim prefix.
 */
internal fun rustComponents(path: Path): List<String> = rustComponentsFor(path.toString(), HOST_WINDOWS)

internal fun rustComponentsFor(
    text: String,
    windows: Boolean,
): List<String> {
    if (!windows) {
        val parts = text.split('/').filter { it.isNotEmpty() && it != "." }
        return if (text.startsWith('/')) listOf("") + parts else parts
    }
    val (prefix, rest) = windowsPrefix(text)
    // A verbatim path is passed to Win32 as it is: `/` there is part of a name.
    val parts = rest.split(*if (prefix.startsWith(VERBATIM)) charArrayOf('\\') else charArrayOf('/', '\\'))
    val names = parts.filter { it.isNotEmpty() && it != "." }
    val rooted = rest.startsWith('\\') || (!prefix.startsWith(VERBATIM) && rest.startsWith('/'))
    return listOfNotNull(prefix.ifEmpty { null }) + (if (rooted || prefix.startsWith('\\')) listOf("") else emptyList()) + names
}

private const val VERBATIM = "\\\\?\\"

/** The prefix Rust's Windows parser finds, and the text after it. */
private fun windowsPrefix(text: String): Pair<String, String> =
    when {
        text.startsWith(VERBATIM) -> verbatimPrefix(text)
        isUncStart(text) -> uncPrefix(text)
        isDrive(text, 0) -> text.substring(0, 2).uppercase() to text.substring(2)
        else -> "" to text
    }

private fun isSeparator(c: Char) = c == '/' || c == '\\'

private fun isDrive(
    text: String,
    at: Int,
) = text.length >= at + 2 && text[at + 1] == ':' && text[at].let { it in 'a'..'z' || it in 'A'..'Z' }

private fun isUncStart(text: String) = text.length > 2 && text.take(2).all(::isSeparator) && !isSeparator(text[2])

private fun verbatimPrefix(text: String): Pair<String, String> {
    val at = VERBATIM.length
    if (isDrive(text, at)) return VERBATIM + text.substring(at, at + 2).uppercase() to text.substring(at + 2)
    val end = text.indexOf('\\', at).let { if (it < 0) text.length else it }
    return text.substring(0, end) to text.substring(end)
}

/** `\\server\share`: both names belong to the prefix. */
private fun uncPrefix(text: String): Pair<String, String> {
    val server = text.indexOfFirst(2, ::isSeparator)
    val share = if (server < 0) -1 else text.indexOfFirst(server + 1, ::isSeparator)
    val end = if (share < 0) text.length else share
    return text.substring(0, end).replace('/', '\\') to text.substring(end)
}

private inline fun String.indexOfFirst(
    from: Int,
    predicate: (Char) -> Boolean,
): Int = (from until length).firstOrNull { predicate(this[it]) } ?: -1

/** Rust orders paths component by component, each by its bytes. */
internal val PATH_ORDER =
    Comparator<Path> { a, b ->
        val x = rustComponents(a)
        val y = rustComponents(b)
        x.zip(y).firstNotNullOfOrNull { (l, r) -> BYTE_ORDER.compare(l, r).takeIf { it != 0 } } ?: x.size.compareTo(y.size)
    }

/** Rust's `Path::file_stem`: before the last dot, unless the only dot leads the name. */
internal fun fileStem(name: String): String? {
    if (name.isEmpty() || name == "..") return null
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}
