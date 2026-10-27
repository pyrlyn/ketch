@file:OptIn(ExperimentalAtomicApi::class)

package ketch.core.report

import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.decide.Decider
import ketch.core.decide.NoDecider
import ketch.core.log.Level
import ketch.core.log.Log
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What the core tells a front end while it works. The core prints nothing: the CLI renders these events as
 * lines and bars, the desktop app as its own views, and a test records them.
 */
enum class Stage { Resolving, Downloading, Verifying, Extracting, Trusting, Installing }

/** One task's identity, unique for the process, so a front end can match a progress event to its bar. */
data class TaskId(
    val value: Long,
) : Comparable<TaskId> {
    fun get(): Long = value

    override fun compareTo(other: TaskId) = value.compareTo(other.value)

    internal companion object {
        private val NEXT = AtomicLong(1)

        fun next() = TaskId(NEXT.fetchAndAdd(1))
    }
}

sealed interface Task {
    data object Batch : Task

    data class Download(
        val label: String,
        val batch: TaskId?,
    ) : Task

    data class Activity(
        val message: String,
    ) : Task

    data class Counter(
        val verb: String,
        val unit: String,
        val total: Long,
    ) : Task
}

sealed interface Event {
    data class Step(
        val packageName: String,
        val stage: Stage,
    ) : Event

    data class Status(
        val verb: String,
        val detail: String,
    ) : Event

    data class Success(
        val verb: String,
        val detail: String,
    ) : Event

    data class Warn(
        val detail: String,
    ) : Event

    data class Note(
        val detail: String,
    ) : Event

    data class Debug(
        val detail: String,
    ) : Event

    data class Began(
        val id: TaskId,
        val task: Task,
    ) : Event

    data class Sized(
        val id: TaskId,
        val name: String,
        val total: Long?,
    ) : Event

    data class Progress(
        val id: TaskId,
        val done: Long,
        val total: Long?,
    ) : Event

    data class Ended(
        val id: TaskId,
        val message: String?,
    ) : Event

    data class Abandoned(
        val id: TaskId,
    ) : Event
}

/** Where events go. Called from any thread. */
fun interface Reporter {
    fun event(event: Event)
}

object Silent : Reporter {
    override fun event(event: Event) = Unit
}

/**
 * Writes each event's text to the log file, then hands the event to [inner]. For a front end that renders the
 * core's events itself and would otherwise leave the log empty.
 */
class LogReporter(
    private val inner: Report?,
) : Reporter {
    override fun event(event: Event) {
        when (event) {
            is Event.Status -> Log.record(Level.Info, "${event.verb} ${event.detail}")
            is Event.Success -> Log.record(Level.Info, "${event.verb} ${event.detail}")
            is Event.Warn -> Log.record(Level.Warn, event.detail)
            is Event.Note -> Log.record(Level.Info, event.detail)
            is Event.Debug -> Log.record(Level.Debug, event.detail)
            is Event.Ended -> event.message?.let { Log.record(Level.Info, "fetched $it") }
            else -> Unit
        }
        inner?.event(event)
    }
}

/** Keeps every event, in order, for a test or a host that replays them. */
class Recorder : Reporter {
    private val events = AtomicReference(emptyList<Event>())

    fun events(): List<Event> = events.load()

    override fun event(event: Event) {
        while (true) {
            val current = events.load()
            if (events.compareAndSet(current, current + event)) return
        }
    }
}

/** The reporter an operation talks to; cheap to pass around, as the reporter behind it is shared. */
class Report(
    private val reporter: Reporter,
) {
    fun event(event: Event) = reporter.event(event)

    fun stage(
        packageName: String,
        stage: Stage,
    ) = event(Event.Step(packageName, stage))

    fun step(
        verb: String,
        detail: String,
    ) = event(Event.Status(verb, detail))

    fun success(
        verb: String,
        detail: String,
    ) = event(Event.Success(verb, detail))

    fun warn(detail: String) = event(Event.Warn(detail))

    fun note(detail: String) = event(Event.Note(detail))

    fun debug(detail: String) = event(Event.Debug(detail))

    /** A spinner for work of unknown length; it ends when closed, or when [Activity.run] returns. */
    fun activity(message: String) = Activity(Handle.begin(this, Task.Activity(message)))

    /** Downloads that belong together; it ends when closed. */
    fun batch() = Batch(Handle.begin(this, Task.Batch))

    fun download(label: String) = Download.begin(this, label, null)

    /** Counts [total] items of [unit]; it ends when closed. */
    fun counter(
        verb: String,
        total: Long,
        unit: String,
    ) = Counter(Handle.begin(this, Task.Counter(verb, unit, total)), total)

    companion object {
        fun silent() = Report(Silent)
    }
}

/** Everything an operation needs from its host. A copy shares the [cancel] token, so a host can still stop it. */
data class Ctx(
    val cfg: Config,
    val report: Report,
    val decider: Decider = NoDecider,
    val cancel: Cancel = Cancel(),
) {
    fun withDecider(decider: Decider) = copy(decider = decider)

    fun withCancel(cancel: Cancel) = copy(cancel = cancel)
}

/** Rust's drop guard: closing sends the task's end. */
class Handle internal constructor(
    internal val report: Report,
    val id: TaskId,
) : AutoCloseable {
    override fun close() = report.event(Event.Ended(id, null))

    internal companion object {
        fun begin(
            report: Report,
            task: Task,
        ): Handle {
            val id = TaskId.next()
            report.event(Event.Began(id, task))
            return Handle(report, id)
        }
    }
}

class Activity internal constructor(
    private val done: Handle,
) : AutoCloseable {
    val id: TaskId get() = done.id

    /** Run [work] under this activity and end it when the work returns or throws. */
    fun <T> run(work: () -> T): T = use { work() }

    override fun close() = done.close()
}

class Batch internal constructor(
    private val done: Handle,
) : AutoCloseable {
    fun download(label: String) = Download.begin(done.report, label, done.id)

    override fun close() = done.close()
}

class Counter internal constructor(
    private val done: Handle,
    private val total: Long,
) : AutoCloseable {
    private val count = AtomicLong(0)

    fun inc() = done.report.event(Event.Progress(done.id, count.addAndFetch(1), total))

    override fun close() = done.close()
}

/** Where a transfer reports its bytes. */
interface ProgressSink {
    fun start(
        total: Long?,
        label: String,
    )

    fun advance(delta: Long)

    fun finish(message: String)
}

object SilentProgress : ProgressSink {
    override fun start(
        total: Long?,
        label: String,
    ) = Unit

    override fun advance(delta: Long) = Unit

    override fun finish(message: String) = Unit
}

/** One transfer. Closed without [finish], it was abandoned: the front end drops its bar rather than ending it. */
class Download private constructor(
    private val report: Report,
    val id: TaskId,
) : ProgressSink,
    AutoCloseable {
    private val done = AtomicLong(0)
    private val total = AtomicReference<Long?>(null)
    private val finished = AtomicBoolean(false)

    override fun start(
        total: Long?,
        label: String,
    ) {
        this.total.store(total)
        done.store(0)
        report.event(Event.Sized(id, label, total))
    }

    override fun advance(delta: Long) = report.event(Event.Progress(id, done.addAndFetch(delta), total.load()))

    override fun finish(message: String) {
        finished.store(true)
        report.event(Event.Ended(id, message.ifEmpty { null }))
    }

    override fun close() {
        if (!finished.load()) report.event(Event.Abandoned(id))
    }

    internal companion object {
        fun begin(
            report: Report,
            label: String,
            batch: TaskId?,
        ): Download {
            val id = TaskId.next()
            report.event(Event.Began(id, Task.Download(label, batch)))
            return Download(report, id)
        }
    }
}
