// Terminal output, the only place the binary prints: the port of src/ui.rs.
//
// The core prints nothing; it says what happens as report events, and `Terminal` is the reporter that draws them
// here — each event through the same helper a command body calls, so a line looks the same whoever said it. Colour,
// verbosity and icons are settings of this renderer, never of the core. Long work is drawn by `activity`: a bar when
// the total is known, a spinner otherwise. The `tui` renderer is not ported yet, so its hooks are left out.
//
// This file holds the settings, the colours and the verb column; Lines.kt builds the lines and Output.kt prints them.
@file:OptIn(ExperimentalForeignApi::class)

package ketch.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.STDERR_FILENO
import platform.posix.getenv
import platform.posix.isatty
import kotlin.concurrent.Volatile

@Volatile
private var colorOn = false

/** Off until the config says otherwise: an error raised while the config is still loading is printed without an icon. */
@Volatile
internal var emojiOn = false

/** 0 quiet, 1 normal, 2 verbose. */
@Volatile
private var level = 1

internal fun env(name: String): String? = getenv(name)?.toKString()

internal fun isTerminal(fd: Int) = isatty(fd) == 1

fun init(
    color: Boolean?,
    quiet: Boolean,
    verbose: Boolean,
) {
    val enabled =
        color ?: (forceColor() || (isTerminal(STDERR_FILENO) && env("NO_COLOR") == null && env("TERM") != "dumb"))
    // Only legacy Windows consoles print escape sequences as text; no target of this build has one.
    colorOn = enabled
    level =
        when {
            quiet -> 0
            verbose -> 2
            else -> 1
        }
}

/**
 * `CLICOLOR_FORCE` set to anything but `0`: colour even into a pipe. The convention (https://bixense.com/clicolors/)
 * is how a pager, a CI log or a test asks for colour it cannot get by being a terminal. It is an explicit request, so
 * it outranks `NO_COLOR`; `--no-color` still outranks it.
 */
private fun forceColor(): Boolean = env("CLICOLOR_FORCE").let { it != null && it.isNotEmpty() && it != "0" }

/**
 * Turn the status-line icons on or off, once the config is known. [wanted] is the `emoji` setting (`KETCH_EMOJI`,
 * `config.toml`) with `--no-emoji` already applied. It is only a wish: see [emojiAllowed].
 */
fun setEmoji(wanted: Boolean) {
    emojiOn = emojiAllowed(wanted, isTerminal(STDERR_FILENO), env("TERM"))
}

/**
 * Icons are for a person reading a terminal. A pipe or a file is read by a program, which would have to strip them,
 * and `TERM=dumb` is a terminal that has said it cannot draw them. Unlike colour, nothing forces them into a pipe:
 * `CLICOLOR_FORCE` asks for escape codes, not for glyphs.
 */
internal fun emojiAllowed(
    wanted: Boolean,
    stderrIsTerminal: Boolean,
    term: String?,
): Boolean = wanted && stderrIsTerminal && term != "dumb"

fun colorEnabled(): Boolean = colorOn

fun isQuiet(): Boolean = level == 0

fun isVerbose(): Boolean = level >= 2

private fun paint(
    code: String,
    text: String,
): String = if (colorOn) "\u001b[${code}m$text\u001b[0m" else text

fun bold(t: String) = paint("1", t)

fun dim(t: String) = paint("2", t)

fun green(t: String) = paint("32", t)

fun yellow(t: String) = paint("33", t)

fun blue(t: String) = paint("34", t)

fun red(t: String) = paint("31", t)

fun cyan(t: String) = paint("36", t)

/**
 * What a piece of status text means. The one place a meaning picks its colour, so a command never chooses red or
 * green by hand and every "this failed" looks the same wherever it is said.
 */
enum class Tone {
    /** Work happening now. */
    Step,

    /** Work that finished well. */
    Success,

    /** Something to look at that does not stop the run, or a question that asks for consent. */
    Warning,

    /** Something that failed. */
    Error,

    /** An aside. */
    Note,

    /** A suggestion, or a question that asks for an answer. */
    Hint,
}

/** Paint [text] in the colour its [Tone] carries, when colour is on. */
fun tone(
    tone: Tone,
    text: String,
): String =
    when (tone) {
        Tone.Step -> blue(text)
        Tone.Success -> green(text)
        Tone.Warning -> yellow(text)
        Tone.Error -> red(text)
        Tone.Note -> dim(text)
        Tone.Hint -> cyan(text)
    }

/** The icon a line carries: its operation's when the verb names one, else its meaning's. Work in progress and questions have none. */
internal fun icon(
    verb: String,
    kind: Tone,
): String? =
    icons.icon(verb) ?: when (kind) {
        Tone.Success -> "✅"
        Tone.Warning -> "❗"
        Tone.Error -> "❌"
        Tone.Note -> "💡"
        Tone.Step, Tone.Hint -> null
    }

/** The icon gutter in front of the verb column, or nothing when icons are off. */
private fun gutter(icon: String?): String = if (emojiOn) icons.gutter(icon) else ""

/** The width of the verb column, icon gutter included: where the text after the verb starts, for lines that continue under it. */
internal fun labelWidth(): Int = if (emojiOn) VERB_WIDTH + icons.ICON_WIDTH + 1 else VERB_WIDTH

private const val VERB_WIDTH = 10

/** The right-aligned verb column every status line starts with, behind the icon its verb and [Tone] pick. */
internal fun label(
    verb: String,
    kind: Tone,
): String = gutter(icon(verb, kind)) + verb.padStart(VERB_WIDTH)

/** The verb column with no icon, but the same width as one that has it. */
internal fun bareLabel(verb: String): String = gutter(null) + verb.padStart(VERB_WIDTH)
