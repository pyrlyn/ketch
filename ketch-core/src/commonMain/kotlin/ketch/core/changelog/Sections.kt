// Nothing here trusts a changelog's structure. A changelog is prose someone else wrote; the section matcher takes what
// it recognises and says plainly when it recognises nothing, rather than guessing a range and printing the wrong
// release's history.
package ketch.core.changelog

import ketch.core.model.Release
import ketch.core.model.Version
import ketch.core.text.asciiLowercase
import ketch.core.text.codePointAt
import ketch.core.text.codePoints
import ketch.core.text.isAsciiAlphanumeric
import ketch.core.text.isRustWhitespace
import ketch.core.text.rustLines
import ketch.core.text.rustTrim
import kotlinx.io.files.Path

/** Where a changelog came from, so the caller can say so. */
sealed interface Origin {
    /** A file inside the installed payload. */
    data class File(
        val path: Path,
    ) : Origin

    /** Notes published with the release. */
    data object Release : Origin
}

/** One changelog, ready to print. */
data class Entry(
    val origin: Origin,
    /** The heading the section was found under, when there was one. */
    val heading: String?,
    val body: String,
)

/** The notes a release published, if it published any. */
fun fromRelease(notes: String?): Entry? {
    val body = sanitize(notes?.rustTrim() ?: return null)
    return if (body.isEmpty()) null else Entry(Origin.Release, null, body)
}

/**
 * Strip what a changelog has no business containing.
 *
 * This is a client app's bytes on their way to a terminal. An escape sequence in one can rewrite lines already on
 * screen or drive the terminal itself, and a bidi override can make a line read as the reverse of what it says. Neither
 * belongs in prose, so both are dropped where the text enters rather than where it is printed, which would leave every
 * caller to remember.
 */
fun sanitize(text: String): String = codePoints(text).filter(::belongsInProse).joinToString("")

// `isISOControl` is C0, DEL and C1, as Rust's `is_control` — including U+009B, which some terminals still take as a CSI
// introducer on its own.
private fun belongsInProse(point: String) = point == "\n" || point == "\t" || !(point[0].isISOControl() || codePointAt(point, 0).let { c -> INVISIBLE.any { c in it } })

/**
 * Characters that take up no space but change how the text around them is laid out: bidi controls and marks,
 * zero-width characters, line and paragraph separators, and the invisible tag block. Dropping only the overrides leaves
 * U+202E's quieter relatives — LRM, RLM, ALM — free to reorder a line in any bidi-aware renderer.
 */
private val INVISIBLE =
    listOf(0x00AD..0x00AD, 0x061C..0x061C, 0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0xFEFF..0xFEFF, 0xE0000..0xE007F)

/**
 * The block of a Markdown changelog belonging to one version: the heading it matched and the lines under it, up to the
 * next heading at the same level or higher. Null when no heading names this version — a caller that printed the whole
 * file in that case is being honest; one that printed a guessed range would not be.
 */
fun section(
    text: String,
    version: String,
): Pair<String?, String>? {
    val wanted = normalise(version)
    val lines = rustLines(text)
    val heads = headings(lines)
    val at = heads.indexOfFirst { headingNames(it.text, wanted) }
    if (wanted.isEmpty() || at < 0) return null
    val head = heads[at]
    val end = heads.drop(at + 1).firstOrNull { it.level <= head.level }?.start ?: lines.size
    return head.text to lines.subList(head.body, end).joinToString("\n").rustTrim()
}

/** One heading: where its text is, where its body starts, and how deep it sits. */
private class Heading(
    val start: Int,
    val body: Int,
    val level: Int,
    val text: String,
)

/**
 * Every heading in the file, in order. Fenced code blocks are skipped: a shell snippet's `# 1.2.2 is the one to pin` is
 * a comment, and read as a level-1 heading it ends the section it sits in — or starts one in the middle of a snippet.
 */
private fun headings(lines: List<String>): List<Heading> {
    val out = mutableListOf<Heading>()
    var fenced = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val level = atxLevel(line)
        when {
            fenceLine(line) -> {
                fenced = !fenced
            }

            fenced -> {}

            level != null -> {
                out += Heading(i, i + 1, level, line.rustTrim())
            }

            underlined(line, lines.getOrElse(i + 1) { "" }) -> {
                out += Heading(i, i + 2, 1, line.rustTrim())
                i++
            }
        }
        i++
    }
    return out
}

/** A ``` or ~~~ fence, with or without an info string. */
private fun fenceLine(line: String): Boolean {
    val trimmed = line.trimStart { it.isRustWhitespace() }
    return trimmed.startsWith("```") || trimmed.startsWith("~~~")
}

private const val MAX_ATX = 6

/** `##` and friends. `#####hi` is not a heading; `## 1.2.3` is. */
internal fun atxLevel(line: String): Int? {
    val hashes = line.takeWhile { it == '#' }.length
    return hashes.takeIf { it in 1..MAX_ATX && (hashes == line.length || line[hashes] == ' ') }
}

/**
 * A Setext heading: text with `====` under it, which is how ripgrep and every changelog in its lineage marks a release.
 *
 * Only `=`. The other Setext underline is `---`, and a changelog that puts a `---` rule between releases — a common
 * habit — would have every section truncated at the line above the rule. Missing those headings costs a fallback to the
 * release notes; matching them wrongly costs a silently half-printed release.
 */
private fun underlined(
    text: String,
    next: String,
): Boolean {
    val rule = next.trimEnd { it.isRustWhitespace() }
    return text.rustTrim().isNotEmpty() && atxLevel(text) == null && rule.length >= 2 && rule.all { it == '=' }
}

/**
 * True when a heading names this version. Changelog headings are written every way there is — `## [1.2.3] - 2024-05-01`,
 * `## v1.2.3`, `## 1.2.3 (2024-05-01)`, `## Release 1.2.3` — so the version is matched as a whole token anywhere in the
 * heading rather than by shape.
 */
private fun headingNames(
    line: String,
    wanted: String,
) = line
    .trimStart('#')
    .splitWhere { !(it.isAsciiAlphanumeric() || it == '.' || it == '-' || it == '+') }
    .any { normalise(it) == wanted }

private fun String.splitWhere(separator: (Char) -> Boolean): List<String> {
    val parts = mutableListOf<String>()
    var from = 0
    forEachIndexed { i, c ->
        if (separator(c)) {
            parts += substring(from, i)
            from = i + 1
        }
    }
    parts += substring(from)
    return parts
}

/** Strip the `v` a tag carries and a release's surrounding punctuation, so `v1.2.3`, `[1.2.3]` and `1.2.3` are one version. */
private fun normalise(text: String): String {
    val trimmed = text.rustTrim().trim { !it.isAsciiAlphanumeric() }
    val bare = trimmed.removePrefix("v").takeIf { trimmed.startsWith('v') && it.firstOrNull() in '0'..'9' }
    return (bare ?: trimmed).asciiLowercase()
}

/**
 * The releases newer than [from] and no newer than [to], newest first.
 *
 * Drafts never count, and a prerelease counts only when [prerelease] allows one: a source or plugin that returns one it
 * was not asked for must not put a prerelease's notes in front of a stable install.
 */
fun between(
    releases: List<Release>,
    from: Version?,
    to: Version?,
    prerelease: Boolean,
): List<Release> =
    releases
        .filter { !it.draft && (prerelease || !(it.prerelease || it.version.isPrerelease)) }
        .filter { from == null || it.version > from }
        .filter { to == null || it.version <= to }
        .sortedByDescending { it.version }
