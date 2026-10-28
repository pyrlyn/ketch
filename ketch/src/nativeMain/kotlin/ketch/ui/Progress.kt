// The part of indicatif ketch draws with: bars and spinners on stderr, several at once, with status lines printed
// above them. Kept to ketch's own five templates rather than taking a terminal library: mordant, the maintained one
// for Kotlin/Native, brings its own terminal layer and number formats, and ketch's bars must read as Rust ketch's.
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package ketch.ui

import cli.plain
import icons.displayWidth
import ketch.core.text.truncate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.STDERR_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.ioctl
import platform.posix.sched_yield
import platform.posix.winsize
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** A lock for state the tick thread and the work share; nothing it guards blocks. */
internal class SpinLock {
    private val busy = AtomicBoolean(false)

    fun <T> locked(body: () -> T): T {
        while (!busy.compareAndSet(expectedValue = false, newValue = true)) sched_yield()
        try {
            return body()
        } finally {
            busy.store(false)
        }
    }
}

/** How a bar draws itself: one line, from its state. */
internal fun interface BarStyle {
    fun render(bar: ProgressBar): String
}

/** One bar or spinner. Hidden until it is put on the [Screen]; a hidden bar keeps its state, so a test can read it. */
internal class ProgressBar(
    length: Long? = null,
) {
    @Volatile
    var length: Long? = length

    private val pos = AtomicLong(0)

    @Volatile
    var message: String = ""
        set(value) {
            field = value
            Screen.changed()
        }

    @Volatile
    var style: BarStyle = BarStyle { it.message }

    /** The spinner frame, moved on by every tick. */
    @Volatile
    var frame = 0

    private val started = TimeSource.Monotonic.markNow()

    @Volatile
    private var ticker: Job? = null

    val position: Long get() = pos.load()

    fun inc(delta: Long) {
        pos.addAndFetch(delta)
        Screen.changed()
    }

    fun setPosition(position: Long) {
        pos.store(position)
        Screen.changed()
    }

    /** Bytes a second since the bar was made, as indicatif's `{bytes_per_sec}` shows them. */
    fun perSecond(): Long {
        val seconds = started.elapsedNow().inWholeMilliseconds / MILLIS
        return if (seconds > 0) (position / seconds).toLong() else 0
    }

    /** indicatif's `ProgressDrawTarget::stderr()`: drawn on stderr when it is a terminal, hidden otherwise. */
    fun drawOnStderr() {
        if (isTerminal(STDERR_FILENO)) Screen.show(this, null)
    }

    /** Redraw on a timer, so a spinner moves while the work blocks. */
    fun enableSteadyTick(millis: Long) {
        disableSteadyTick()
        ticker =
            TICKS.launch {
                while (isActive) {
                    delay(millis)
                    frame++
                    Screen.changed(force = true)
                }
            }
    }

    fun disableSteadyTick() {
        ticker?.cancel()
        ticker = null
    }

    fun finishAndClear() {
        disableSteadyTick()
        Screen.hide(this)
    }

    private companion object {
        const val MILLIS = 1000.0
        val TICKS = CoroutineScope(Dispatchers.Default)
    }
}

/** indicatif's `MultiProgress`: bars that share the screen, cleared together. */
internal class Group {
    fun add(bar: ProgressBar) = Screen.show(bar, this)

    fun remove(bar: ProgressBar) = Screen.hide(bar)

    fun clear() = Screen.hideGroup(this)
}

/**
 * The bottom of stderr while bars are on it. One screen draws every bar, standalone or in a group, so a status line
 * printed through [printAbove] always lands above them and two bars never overwrite each other.
 */
internal object Screen {
    private class Entry(
        val bar: ProgressBar,
        val group: Group?,
    )

    private val lock = SpinLock()
    private val entries = mutableListOf<Entry>()
    private var drawn = 0
    private var last = TimeSource.Monotonic.markNow()

    fun show(
        bar: ProgressBar,
        group: Group?,
    ) = lock.locked {
        if (entries.none { it.bar === bar }) entries += Entry(bar, group)
        draw()
    }

    fun hide(bar: ProgressBar) =
        lock.locked {
            if (entries.removeAll { it.bar === bar }) draw()
        }

    fun hideGroup(group: Group) =
        lock.locked {
            if (entries.removeAll { it.group === group }) draw()
        }

    /** A bar's state moved: redraw, at most every [MIN_GAP_MS] unless [force]d, so a fast download is not all redraws. */
    fun changed(force: Boolean = false) =
        lock.locked {
            if (entries.isNotEmpty() && (force || last.elapsedNow().inWholeMilliseconds >= MIN_GAP_MS)) draw()
        }

    /** Take the bars off the screen, run [print], and draw them again underneath what it wrote. */
    fun printAbove(print: () -> Unit) =
        lock.locked {
            if (drawn > 0) eprint(erase())
            drawn = 0
            print()
            if (entries.isNotEmpty()) draw()
        }

    /** Back to the start of the first drawn line, and clear from there down. */
    private fun erase(): String = "\r" + "\u001b[1A".repeat((drawn - 1).coerceAtLeast(0)) + "\u001b[J"

    private fun draw() {
        val width = stderrWidth()
        val lines = entries.map { fit(it.bar.style.render(it.bar), width) }
        eprint((if (drawn > 0) erase() else "") + lines.joinToString("\n"))
        drawn = lines.size
        last = TimeSource.Monotonic.markNow()
    }

    /** A line that wraps would break the count of lines to move back over, so a long one is cut, without its colour. */
    private fun fit(
        line: String,
        width: Int?,
    ): String {
        if (width == null || displayWidth(plain(line)) < width) return line
        return truncate(plain(line), width - 1)
    }

    private const val MIN_GAP_MS = 50
}

private fun stderrWidth(): Int? =
    memScoped {
        val size = alloc<winsize>()
        if (ioctl(STDERR_FILENO, TIOCGWINSZ.convert(), size.ptr) == 0 && size.ws_col.toInt() > 0) size.ws_col.toInt() else null
    }

/** indicatif's default spinner. */
private const val SPINNER = "⠁⠂⠄⡀⢀⠠⠐⠈"

private fun spinner(bar: ProgressBar): String = SPINNER[bar.frame.mod(SPINNER.length)].toString()

/** `{msg:<28}`: padded, not cut; the caller has truncated it. */
private fun padded(bar: ProgressBar) = bar.message + " ".repeat((LABEL - displayWidth(bar.message)).coerceAtLeast(0))

/** `{bar:24.cyan/blue}` with `progress_chars("=> ")`. */
private fun gauge(bar: ProgressBar): String {
    val total = bar.length ?: 0
    val fraction = if (total > 0) (bar.position.toDouble() / total).coerceIn(0.0, 1.0) else 1.0
    val fill = (fraction * GAUGE).toInt()
    val head = if (fraction > 0 && fill < GAUGE) ">" else ""
    val empty = GAUGE - fill - head.length
    return cyan("=".repeat(fill) + head) + blue(" ".repeat(empty))
}

/** indicatif's `HumanBytes`: binary prefixes with two decimals, plain bytes under one KiB. */
internal fun humanBytes(n: Long): String {
    if (n < KIB) return "$n B"
    var value = n.toDouble()
    var unit = 0
    while (value >= KIB && unit < UNITS.size - 1) {
        value /= KIB
        unit++
    }
    val hundredths = kotlin.math.round(value * HUNDRED).toLong()
    return "${hundredths / HUNDRED.toLong()}.${(hundredths % HUNDRED.toLong()).toString().padStart(2, '0')} ${UNITS[unit]}"
}

private const val LABEL = 28
private const val GAUGE = 24
private const val BYTES = 10
private const val RATE = 11
private const val KIB = 1024
private const val HUNDRED = 100.0
private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

/** `  {msg:<28} [{bar:24.cyan/blue}] {bytes:>10}/{total_bytes} {bytes_per_sec:>11}`: a download of known size. */
internal val DOWNLOAD_BAR =
    BarStyle {
        "  ${padded(it)} [${gauge(it)}] ${humanBytes(it.position).padStart(BYTES)}/${humanBytes(it.length ?: 0)} " +
            "${humanBytes(it.perSecond())}/s".padStart(RATE)
    }

/** `  {msg:<28} {spinner} {bytes:>10}`: a download of unknown size. */
internal val DOWNLOAD_SPINNER = BarStyle { "  ${padded(it)} ${spinner(it)} ${humanBytes(it.position).padStart(BYTES)}" }

/** `  {msg:<28} [{bar:24.cyan/blue}] {pos}/{len}`: work counted in units. */
internal val ACTIVITY_BAR = BarStyle { "  ${padded(it)} [${gauge(it)}] ${it.position}/${it.length ?: 0}" }

/** `{spinner:.cyan} {msg}`: work of unknown length. */
internal val ACTIVITY_SPINNER = BarStyle { "${cyan(spinner(it))} ${it.message}" }

/** `{msg} {pos}/{len} <unit>`: items checked off. */
internal fun counterStyle(unit: String) = BarStyle { "${it.message} ${it.position}/${it.length ?: 0} $unit" }
