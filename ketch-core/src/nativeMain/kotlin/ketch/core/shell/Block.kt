package ketch.core.shell

/** Opens the block ketch owns. Must begin a line and end one. */
internal const val BEGIN = "# >>> ketch >>>"

/** Closes the block ketch owns. */
internal const val END = "# <<< ketch <<<"

internal const val BOM = '﻿'

/** Single-quote for the POSIX family, where the only character that cannot appear inside single quotes is the quote. */
internal fun quotePosix(text: String) = "'${text.replace("'", "'\\''")}'"

/** Single-quote for fish, which unlike POSIX honours backslash escapes inside single quotes: a backslash is doubled. */
internal fun quoteFish(text: String) = "'${text.replace("\\", "\\\\").replace("'", "\\'")}'"

/**
 * Rust `str::lines`: split on `\n`, drop one `\r` before it, and yield no empty line after a final newline.
 */
private fun rustLines(text: String): List<String> {
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    return if (text.endsWith('\n') || text.isEmpty()) lines.dropLast(1) else lines
}

/**
 * True when some line the shell will actually run names this directory.
 *
 * Comments are skipped so that a file still carrying a commented-out attempt, or ketch's own markers, does not read as
 * configured. The name has to stand on its own, too: a bare substring test accepts `…/.ketch/bin.bak`, a backup of the
 * file, so `path install` would report the directory as already set up and add nothing, and `doctor` would point at a
 * new shell that still lacks it.
 */
internal fun mentions(
    text: String,
    binDir: String,
): Boolean =
    binDir.isNotEmpty() &&
        rustLines(text)
            .filter { !it.trimStart().startsWith('#') }
            .any { line -> standaloneMatches(line, binDir) }

/**
 * What can sit next to a directory on `PATH`: a separator, a quote, a space. Another path character (the `.` of
 * `.bak`, the `/` of a longer path) means a different directory that merely starts the same.
 */
private fun isBoundary(c: Char) = c in " \t\n\u000C\r" || c in ":\"'=()"

/** Rust `match_indices`: matches found left to right, never overlapping. */
private fun standaloneMatches(
    line: String,
    needle: String,
): Boolean {
    var start = line.indexOf(needle)
    while (start >= 0) {
        val end = start + needle.length
        if (standsAlone(line, start, end)) return true
        start = line.indexOf(needle, end)
    }
    return false
}

private fun standsAlone(
    line: String,
    start: Int,
    end: Int,
) = (start == 0 || isBoundary(line[start - 1])) && (end == line.length || isBoundary(line[end]))

/** Char range of the ketch block, markers and trailing newline included. */
internal fun blockSpan(text: String): Pair<Int, Int>? {
    val start = markerAtLineStart(text, BEGIN, 0) ?: return null
    return markerAtLineStart(text, END, start + BEGIN.length)?.let { start to pastLineEnd(text, it + END.length) }
}

/**
 * [at] moved past the line ending there. CRLF profiles (PowerShell, autocrlf dotfiles) end the marker line with
 * `\r\n`; consuming only `\n` would leave the `\r` behind.
 */
private fun pastLineEnd(
    text: String,
    at: Int,
) = when {
    text.startsWith("\r\n", at) -> at + 2
    text.getOrNull(at) == '\n' || text.getOrNull(at) == '\r' -> at + 1
    else -> at
}

/**
 * Offset of [marker] where it occupies a whole line, at or after [from].
 *
 * Whole-line matching is what keeps a marker quoted inside somebody's own script from being mistaken for the block
 * ketch owns. `\r` counts as a line boundary because PowerShell profiles and autocrlf dotfiles end lines with `\r\n`.
 */
internal fun markerAtLineStart(
    text: String,
    marker: String,
    from: Int,
): Int? {
    var at = text.indexOf(marker, from)
    while (at >= 0) {
        val startsLine = at == 0 || text[at - 1] == '\n' || text[at - 1] == '\r' || (at == 1 && text[0] == BOM)
        val rest = at + marker.length
        val endsLine = rest == text.length || text[rest] == '\n' || text[rest] == '\r'
        if (startsLine && endsLine) return at
        at = text.indexOf(marker, rest)
    }
    return null
}

/** Put [block] into [text], replacing any block already there. Null means the file already says exactly this. */
internal fun splice(
    text: String,
    block: String,
): String? {
    val span = blockSpan(text)
    if (span != null) {
        val next = text.substring(0, span.first) + block + text.substring(span.second)
        return next.takeIf { it != text }
    }
    val head =
        when {
            text.isEmpty() -> ""
            text.endsWith('\n') -> "$text\n"
            else -> "$text\n\n"
        }
    return head + block
}

/** Take the block out. Null means there was none. */
internal fun unsplice(text: String): String? {
    val (start, end) = blockSpan(text) ?: return null
    val head = text.substring(0, start)
    // The blank line inserted ahead of the block goes back out with it, so installing and uninstalling repeatedly
    // cannot grow the file.
    val trimmed = head.removeSuffix("\n").takeIf { it.length < head.length && it.endsWith('\n') } ?: head
    return trimmed + text.substring(end)
}

/** What [unspliceProfile] leaves of a PowerShell profile. */
internal sealed interface ProfileEdit {
    /** The profile with the block taken out. */
    data class Rewrite(
        val text: String,
    ) : ProfileEdit

    /** Nothing but the block (and a byte order mark ketch may have written) was in it. */
    data object Delete : ProfileEdit
}

/**
 * Take the block out of a profile; null when there was none. A profile left empty can go: an empty profile still trips
 * an execution policy that forbids scripts.
 */
internal fun unspliceProfile(text: String): ProfileEdit? {
    val next = unsplice(text) ?: return null
    return if (next.trimStart(BOM).isEmpty()) ProfileEdit.Delete else ProfileEdit.Rewrite(next)
}
