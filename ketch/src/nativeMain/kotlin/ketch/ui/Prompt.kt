// The questions of ui.rs: yes/no, free text and a numbered pick.
package ketch.ui

import ketch.core.error.KetchError
import ketch.core.log.Level
import ketch.core.log.Log
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO

/** Whether a question can be put to a person: stdin is a terminal. */
fun canAsk(): Boolean = isTerminal(STDIN_FILENO)

/** Ask a yes/no question. Returns [default] when stdin is not a terminal, so scripts never hang on input that will not come. */
fun confirm(
    question: String,
    default: Boolean,
): Boolean {
    val answered = ask(question, default)
    if (!answered) {
        // Callers return on a decline as on success, so say why nothing happened — especially when the decline came
        // from a non-interactive stdin rather than a person. Shown under `--quiet` too: "it did nothing and said
        // nothing" is not quiet, it is a bug report waiting to happen.
        Log.record(Level.Info, "cancelled $question")
        emit(cancelledLine(question))
    }
    return answered
}

/** A yes/no question whose decline does not cancel the command, which continues either way (stop a process, or leave it). */
fun offer(
    question: String,
    default: Boolean,
): Boolean = ask(question, default)

/**
 * A yes/no question that is a form field rather than consent: "no" is an answer, not a cancellation, so nothing is
 * announced when it is declined. Piped answers are read, as the questionnaire's tests and scripts give them.
 */
fun question(
    question: String,
    default: Boolean,
): Boolean {
    if (isTerminal(STDIN_FILENO)) return ask(question, default)
    return readlnOrNull()?.let { booleanAnswer(it, default) } ?: default
}

/**
 * `--quiet` deliberately does not reach here. It asks for less output, not for consent: silently taking the default
 * answer to "remove this?" is not a quieter version of asking, it is a different program.
 */
private fun ask(
    question: String,
    default: Boolean,
): Boolean {
    if (!isTerminal(STDIN_FILENO)) return default
    eprint(confirmLine(question, default))
    return readlnOrNull()?.let { booleanAnswer(it, default) } ?: default
}

/** One questionnaire or confirmation answer. */
internal fun booleanAnswer(
    answer: String,
    default: Boolean,
): Boolean =
    when (answer.trim().lowercase()) {
        "y", "yes" -> true
        "n", "no" -> false
        else -> default
    }

/**
 * Ask one question and read one line of free text. Unlike [confirm] this reads stdin when it is not a terminal: the
 * answer is the point rather than consent, and piping answers in is how the questionnaire is scripted and tested. End
 * of input is an empty answer, so a pipe with nothing in it still gets the default rather than a hang.
 */
fun prompt(
    question: String,
    default: String,
): String {
    eprint("${promptLine(Tone.Hint, "answer", question)} [${printable(default.ifEmpty { "none" })}] ")
    return readlnOrNull()?.trim()?.ifEmpty { null } ?: default
}

/**
 * Ask for a field that has no default. End of input is an error rather than an answer: inventing a `source` would
 * write a config nobody asked for and say nothing about it.
 */
fun promptRequired(question: String): String {
    eprint("${promptLine(Tone.Hint, "answer", question)} ")
    val answer =
        readlnOrNull() ?: throw KetchError.Msg("$question needs an answer, and stdin has none — run this in a terminal")
    return answer.trim().ifEmpty { throw KetchError.Msg("$question has no default and cannot be left empty") }
}

/**
 * Ask the user to pick one of [options] by number; the index of the pick.
 *
 * Null when nobody can answer: stdin or stderr is not a terminal, or the input ended. Unlike [prompt], a pipe does
 * not answer this: a number read from a script picks by position, and position is the very thing that differs
 * between platforms. The options come from a client app's payload, so they are printed through [printable].
 */
fun select(
    question: String,
    options: List<String>,
): Int? {
    if (!isTerminal(STDIN_FILENO) || !isTerminal(STDERR_FILENO)) return null
    eprint("${promptLine(Tone.Hint, "choose", question)}\n")
    options.forEachIndexed { i, option -> eprint("${"${i + 1})".padStart(labelWidth() + 1)} ${printable(option)}\n") }
    var pick: Int? = null
    while (pick == null) {
        eprint("${promptLine(Tone.Hint, "answer", "number, 1 to ${options.size}")}: ")
        val answer = readlnOrNull() ?: break
        pick = answer.trim().toIntOrNull()?.takeIf { it in 1..options.size }
        if (pick == null) warn("answer with a number from 1 to ${options.size}")
    }
    return pick?.minus(1)
}
