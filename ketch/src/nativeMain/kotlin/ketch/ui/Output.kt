// The status lines and data output of ui.rs.
@file:OptIn(ExperimentalForeignApi::class)

package ketch.ui

import ketch.core.error.KetchError
import ketch.core.log.Level
import ketch.core.log.Log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

/**
 * Every status line leaves through here. While bars are on screen they own the bottom of the terminal, and a bare
 * write lands in the middle of one, so the screen takes them off, prints the line, and draws them again under it.
 */
internal fun emit(line: String) = Screen.printAbove { eprint("$line\n") }

/** Write to stderr now: a prompt is read straight after it, and the bars redraw over whatever is left unflushed. */
internal fun eprint(text: String) {
    fputs(text, stderr)
    fflush(stderr)
}

/** Status line for a step that is happening now. */
fun step(
    verb: String,
    detail: String,
) {
    // Logged before the level check: `--quiet` is about this terminal, and the log is there to keep the run.
    Log.record(Level.Info, "$verb $detail")
    if (!isQuiet()) emit(stepLine(verb, detail))
}

/** Something finished well. */
fun success(
    verb: String,
    detail: String,
) {
    Log.record(Level.Info, "$verb $detail")
    if (!isQuiet()) emit(successLine(verb, detail))
}

/** A warning that is not written to the log, for the one caller that cannot: the log failing to open. */
fun warnUnlogged(detail: String) {
    if (!isQuiet()) emit(warnLine(detail))
}

/** Something the user should know but that does not stop the run. */
fun warn(detail: String) {
    Log.record(Level.Warn, detail)
    if (!isQuiet()) emit(warnLine(detail))
}

/** A failure that is the whole message, with no `error` label, hint or details: `<name>: not found`. Shown under `--quiet` too. */
fun bareError(line: String) {
    Log.record(Level.Error, line)
    emit(tone(Tone.Error, printable(line)))
}

/** An aside: true, worth saying once, and not a problem. */
fun note(detail: String) {
    Log.record(Level.Info, detail)
    if (!isQuiet()) emit(noteLine(detail))
}

/** Only shown with `--verbose`, but always written to the log. */
fun debug(detail: String) {
    Log.record(Level.Debug, detail)
    if (isVerbose()) emit(debugLine(detail))
}

/** Fatal error rendering, with details and a hint when there is one. */
fun error(err: KetchError) {
    val details = err.details()
    val hint = err.hint()
    // One record, so a failure is one entry in the log rather than three lines a reader has to piece together.
    val logged = StringBuilder(err.message)
    details.forEach { logged.append('\n').append(it) }
    hint?.let { logged.append("\nhint: ").append(it) }
    Log.record(Level.Error, logged.toString())
    errorLines(err.message, details, hint).forEach(::emit)
}

/** Data output. Unlike the status helpers this goes to stdout, so `ketch list` can be piped while progress still shows. */
fun out(line: String) = println(line)
