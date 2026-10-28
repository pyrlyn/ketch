// Rendering the core's events, and the person at this terminal as the core's decider: from ui.rs.
package ketch.ui

import ketch.core.config.Config
import ketch.core.decide.Decider
import ketch.core.process.Occupant
import ketch.core.report.Ctx
import ketch.core.report.Event
import ketch.core.report.ProgressSink
import ketch.core.report.Report
import ketch.core.report.Reporter
import ketch.core.report.Task
import ketch.core.report.TaskId
import kotlinx.io.files.Path

/** What a begun task has on screen until it ends. */
private sealed interface Live {
    class Batch(
        val bars: Bars,
    ) : Live

    class Download(
        val sink: ProgressSink,
        var done: Long = 0,
    ) : Live

    /** Held for its close, which clears the spinner. */
    class Spinner(
        val activity: Activity,
    ) : Live

    class Count(
        val counter: Counter,
        var done: Long = 0,
    ) : Live
}

/**
 * Renders the core's [Event]s as this module's lines and bars. Each event lands on the helper that draws the same
 * thing for a command body — a status line on [step], a download on the bar [progressFor] or a batch's [Bars.sink]
 * picks — so what reaches the terminal and the log does not depend on who said it.
 */
class Terminal : Reporter {
    private val lock = SpinLock()
    private val live = mutableMapOf<TaskId, Live>()

    private fun <T> withLive(body: (MutableMap<TaskId, Live>) -> T): T = lock.locked { body(live) }

    private fun began(
        id: TaskId,
        task: Task,
    ) {
        val shown =
            when (task) {
                is Task.Batch -> {
                    Live.Batch(bars())
                }

                is Task.Download -> {
                    val inBatch = task.batch?.let { b -> (withLive { it[b] } as? Live.Batch)?.bars?.sink(task.label) }
                    Live.Download(inBatch ?: progressFor())
                }

                is Task.Activity -> {
                    Live.Spinner(activity(task.message, null))
                }

                is Task.Counter -> {
                    Live.Count(counter(task.verb, task.total, task.unit))
                }
            }
        withLive { it[id] = shown }
    }

    private fun advanced(
        id: TaskId,
        now: Long,
    ) {
        when (val shown = withLive { it[id] }) {
            is Live.Download -> {
                shown.sink.advance((now - shown.done).coerceAtLeast(0))
                shown.done = now
            }

            is Live.Count -> {
                repeat((now - shown.done).coerceAtLeast(0).toInt()) { shown.counter.inc() }
                shown.done = now
            }

            else -> {}
        }
    }

    // Taken out first and finished after the lock is released: finishing takes the screen's own lock, and a status
    // line printed meanwhile must not wait on this map.
    private fun ended(
        id: TaskId,
        message: String?,
    ) = close(withLive { it.remove(id) }, message)

    private fun close(
        shown: Live?,
        message: String?,
    ) {
        when (shown) {
            is Live.Batch -> shown.bars.close()
            is Live.Download -> shown.sink.finish(message.orEmpty())
            is Live.Spinner -> shown.activity.close()
            is Live.Count -> shown.counter.close()
            null -> Unit
        }
    }

    override fun event(event: Event) {
        when (event) {
            // Stages are drawn only by the interactive renderer, which is not ported; line output says them as steps.
            is Event.Step -> {}

            is Event.Status -> {
                step(event.verb, event.detail)
            }

            is Event.Success -> {
                success(event.verb, event.detail)
            }

            is Event.Warn -> {
                warn(event.detail)
            }

            is Event.Note -> {
                note(event.detail)
            }

            is Event.Debug -> {
                debug(event.detail)
            }

            is Event.Began -> {
                began(event.id, event.task)
            }

            is Event.Sized -> {
                sized(event)
            }

            is Event.Progress -> {
                advanced(event.id, event.done)
            }

            is Event.Ended -> {
                ended(event.id, event.message)
            }

            is Event.Abandoned -> {
                abandon(withLive { it.remove(event.id) })
            }
        }
    }

    private fun sized(event: Event.Sized) {
        val shown = withLive { it[event.id] } as? Live.Download ?: return
        shown.sink.start(event.total, event.name)
        shown.done = 0
    }

    /** A dropped download leaves no `fetched` line; anything else is cleared as if it ended. */
    private fun abandon(shown: Live?) {
        if (shown is Live.Download) (shown.sink as? BarProgress)?.bar?.finishAndClear() else close(shown, null)
    }
}

/** The [Decider] for a person at this terminal: prompts, which already answer "nobody" when stdin or stderr is not a terminal. */
object TerminalDecider : Decider {
    override fun chooseBinary(
        pkg: String,
        candidates: List<String>,
    ): Int? = select("$pkg ships several binaries sharing its name; which one to link?", candidates)

    override fun stopProcesses(occupants: List<Occupant>): Boolean {
        val question =
            occupants.singleOrNull()?.let { "stop process ${it.pid} using ${it.path}?" }
                ?: "stop ${occupants.size} processes using files being replaced?"
        return offer(question, false)
    }

    override fun confirmRemoveLeftover(path: Path): Boolean = offer("remove leftover $path?", false)
}

private val REPORT by lazy { Report(Terminal()) }

/** The reporter every command hands the core: one [Terminal] for the run. */
fun report(): Report = REPORT

/** A core context for [cfg] that reports to this terminal and asks nothing: a run with no person to answer, or `--yes`. */
fun ctx(cfg: Config): Ctx = Ctx(cfg, report())

/** [ctx], with the pipeline's questions put to a person at this terminal when [ask]; false under `--yes`, which answered them. */
fun ctxAsking(
    cfg: Config,
    ask: Boolean,
): Ctx = if (ask) ctx(cfg).withDecider(TerminalDecider) else ctx(cfg)
