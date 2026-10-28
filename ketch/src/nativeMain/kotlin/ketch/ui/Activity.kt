// Progress as ui.rs draws it: download bars, batches of them, activities and counters.
package ketch.ui

import ketch.core.log.Level
import ketch.core.log.Log
import ketch.core.report.ProgressSink
import ketch.core.report.SilentProgress
import ketch.core.text.truncate
import platform.posix.STDERR_FILENO

private const val LABEL_CHARS = 28
private const val TICK_MS = 120L

/** A real terminal progress bar for one download. */
internal class BarProgress(
    /** The group this bar belongs to, when it is one of several on screen. */
    private val group: Group? = null,
    /** What to call the work, when the caller knows better than the download: four bars named by asset say little. */
    internal var label: String? = null,
) : ProgressSink {
    internal val bar = ProgressBar()

    override fun start(
        total: Long?,
        label: String,
    ) {
        bar.style = if (total != null) DOWNLOAD_BAR else DOWNLOAD_SPINNER
        bar.length = total ?: 0
        bar.message = truncate(printable(this.label ?: label), LABEL_CHARS)
        if (group != null) group.add(bar) else bar.drawOnStderr()
        bar.setPosition(0)
    }

    override fun advance(delta: Long) = bar.inc(delta)

    override fun finish(message: String) {
        bar.finishAndClear()
        group?.remove(bar)
        if (message.isEmpty()) return
        Log.record(Level.Info, "fetched $message")
        // In a batch the download is one step of several and `installed X` follows it directly; a line per asset
        // just pushes that off screen.
        if (!isQuiet() && group == null) emit(successLine("fetched", message))
    }
}

/** A terminal shared by several bars at once, held for the length of a batch. */
internal class Bars(
    private val group: Group?,
) : AutoCloseable {
    /** One bar in this group, named for the work it is doing. */
    fun sink(label: String): ProgressSink = if (group != null) BarProgress(group, label) else SilentProgress

    override fun close() {
        group?.clear()
    }
}

/** Start a group of bars for concurrent work. */
internal fun bars(): Bars = Bars(if (isQuiet() || !isTerminal(STDERR_FILENO)) null else Group())

/** The sink for one download outside a batch. */
internal fun progressFor(): ProgressSink = if (isQuiet() || !isTerminal(STDERR_FILENO)) SilentProgress else BarProgress()

/** Whether [activity] draws a bar or a spinner. */
enum class ActivityKind {
    /** A total was given: a bar of that many units. */
    Bar,

    /** No total: a spinner with no length. */
    Spinner,
}

/**
 * A stderr bar, or a spinner when the length of the work is unknown. Silent when stderr is not a terminal and under
 * `--quiet`. Closing clears the line, including when the work returns early.
 */
class Activity internal constructor(
    internal val bar: ProgressBar,
    val kind: ActivityKind,
) : AutoCloseable {
    /** Move the bar forward. A spinner keeps the count without drawing it; pass a total when the units should show. */
    fun inc(delta: Long) = bar.inc(delta)

    /** Set the absolute position, for a caller that knows `n` of `total`. */
    fun setPosition(position: Long) = bar.setPosition(position)

    /** Replace the message. Client-app text is filtered the way a status line is. */
    fun setMessage(message: String) {
        bar.message = activityMessage(message, kind)
    }

    /** Run [work] under this activity and clear it when the work returns or throws. */
    fun <T> run(work: () -> T): T = use { work() }

    /** Clear the line. */
    override fun close() = bar.finishAndClear()
}

/**
 * Start a bar when [total] is known and a spinner otherwise. The spinner follows rtok's loader: `{spinner:.cyan}
 * {msg}` on stderr, ticking every 120ms. A known total uses the download bar's glyphs, counted in units.
 */
fun activity(
    message: String,
    total: Long?,
): Activity {
    val kind = if (total != null) ActivityKind.Bar else ActivityKind.Spinner
    // Hidden first, so a pipe, `--quiet` and tests still record the mode in the bar's length without drawing.
    val bar = ProgressBar(total)
    bar.message = activityMessage(message, kind)
    if (!isQuiet() && isTerminal(STDERR_FILENO)) {
        bar.style = if (kind == ActivityKind.Bar) ACTIVITY_BAR else ACTIVITY_SPINNER
        Screen.show(bar, null)
        if (kind == ActivityKind.Spinner) bar.enableSteadyTick(TICK_MS)
    }
    return Activity(bar, kind)
}

/** Download bars cap the label so a batch stays aligned; a spinner's message is the whole line, as in rtok's loader. */
private fun activityMessage(
    message: String,
    kind: ActivityKind,
): String = printable(message).let { if (kind == ActivityKind.Bar) truncate(it, LABEL_CHARS) else it }

/** Progress counted in things rather than bytes: `  checking 3/12 packages`, cleared when closed. */
internal class Counter(
    private val bar: ProgressBar?,
) : AutoCloseable {
    fun inc() {
        bar?.inc(1)
    }

    override fun close() {
        bar?.finishAndClear()
    }
}

/** Start a [Counter] of [total] items, labelled like a status line; hidden under `--quiet` and off a terminal. */
internal fun counter(
    verb: String,
    total: Long,
    unit: String,
): Counter {
    if (isQuiet() || !isTerminal(STDERR_FILENO)) return Counter(null)
    val bar = ProgressBar(total)
    bar.style = counterStyle(unit)
    bar.message = tone(Tone.Step, label(verb, Tone.Step))
    Screen.show(bar, null)
    bar.enableSteadyTick(TICK_MS)
    return Counter(bar)
}
