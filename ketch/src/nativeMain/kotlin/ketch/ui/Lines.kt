// The status lines of ui.rs, built rather than printed so a test can read them; Output.kt prints them.
package ketch.ui

import ketch.core.changelog.sanitize

/**
 * Strip what somebody else's text has no business carrying into a terminal.
 *
 * Everything ketch shows that it did not write itself comes from a client app: release asset names, the release
 * notes, a registry `ketch.toml`, a source plugin's output. An escape sequence among them can rewrite the screen
 * above — including the confirmation the user is about to answer — and a bidi override can make a line read as the
 * reverse of what it says. The filter is the changelog's own guard, applied here so that a status line, a warning, a
 * table cell and an error message cannot each forget it. Only the text is filtered, never the colours: painting
 * happens after.
 */
fun printable(text: String): String = sanitize(text)

/** Fold whitespace onto one line so a table row cannot break its columns. */
internal fun foldLine(text: String): String {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (c in text) {
        if (!c.isWhitespace()) {
            word.append(c)
        } else if (word.isNotEmpty()) {
            words += word.toString()
            word.clear()
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words.joinToString(" ")
}

internal fun stepLine(
    verb: String,
    detail: String,
): String = "${tone(Tone.Step, label(verb, Tone.Step))} ${printable(detail)}"

/** The whole line is green, not only the verb: a finished step should read as finished at a glance. */
internal fun successLine(
    verb: String,
    detail: String,
): String = tone(Tone.Success, "${label(verb, Tone.Success)} ${printable(detail)}")

/** The whole warning is yellow. A yellow label with plain text after it reads as a step, and the text is what to act on. */
internal fun warnLine(detail: String): String = tone(Tone.Warning, "${label("warning", Tone.Warning)} ${printable(detail)}")

internal fun noteLine(detail: String): String = tone(Tone.Note, "${label("note", Tone.Note)} ${printable(detail)}")

internal fun debugLine(detail: String): String = tone(Tone.Note, "${bareLabel("debug")} ${printable(detail)}")

/** The headline is red end to end; details stay dim and the hint cyan, so the line that says what failed stands apart. */
internal fun errorLines(
    headline: String,
    details: List<String>,
    hint: String?,
): List<String> {
    val lines = mutableListOf(tone(Tone.Error, "${label("error", Tone.Error)} ${printable(headline)}"))
    details.forEach { lines += "${" ".repeat(labelWidth())} ${tone(Tone.Note, printable(it))}" }
    hint?.let { lines += "${tone(Tone.Hint, label("hint", Tone.Hint))} ${printable(it)}" }
    return lines
}

/** The start of a question: a coloured label, then the question, which can name a client app's asset and is filtered too. */
internal fun promptLine(
    kind: Tone,
    verb: String,
    question: String,
): String = "${tone(kind, bareLabel(verb))} ${printable(question)}"

internal fun confirmLine(
    question: String,
    default: Boolean,
): String = "${promptLine(Tone.Warning, "confirm", question)} ${if (default) "[Y/n]" else "[y/N]"} "

internal fun cancelledLine(question: String): String = promptLine(Tone.Step, "cancelled", question)
