package ketch.serve

import ketch.core.decide.Decider
import ketch.core.process.Occupant
import ketch.core.report.Reporter
import ketch.core.report.Stage
import ketch.core.report.Task
import ketch.wire.Answer
import ketch.wire.Holder
import ketch.wire.Question
import ketch.wire.TaskKind
import kotlinx.io.files.Path
import ketch.core.report.Event as CoreEvent
import ketch.wire.Event as WireEvent
import ketch.wire.Stage as WireStage

/*
 * The core's reporter and decider, onto what the app hears and answers (`ketch-ffi`'s `callbacks.rs`). Cancellation
 * needs no adapter: `ketch serve` holds the core's own `Cancel` and hands it to the call.
 */

/** Where a call's events go, as the app reads them. Called from any thread. */
fun interface Listener {
    fun event(event: WireEvent)
}

/** Who answers a call's questions. An answer may block on a person. */
fun interface Asker {
    fun ask(question: Question): Answer
}

internal fun Stage.toWire() =
    when (this) {
        Stage.Resolving -> WireStage.Resolving
        Stage.Downloading -> WireStage.Downloading
        Stage.Verifying -> WireStage.Verifying
        Stage.Extracting -> WireStage.Extracting
        Stage.Trusting -> WireStage.Trusting
        Stage.Installing -> WireStage.Installing
    }

internal fun Task.toWire() =
    when (this) {
        Task.Batch -> TaskKind.Batch
        is Task.Download -> TaskKind.Download(label, batch?.value)
        is Task.Activity -> TaskKind.Activity(message)
        is Task.Counter -> TaskKind.Counter(verb, unit, total)
    }

internal fun CoreEvent.toWire(): WireEvent =
    when (this) {
        is CoreEvent.Step -> WireEvent.Step(packageName, stage.toWire())
        is CoreEvent.Status -> WireEvent.Status(verb, detail)
        is CoreEvent.Success -> WireEvent.Success(verb, detail)
        is CoreEvent.Warn -> WireEvent.Warn(detail)
        is CoreEvent.Note -> WireEvent.Note(detail)
        is CoreEvent.Debug -> WireEvent.Debug(detail)
        is CoreEvent.Began -> WireEvent.Began(id.value, task.toWire())
        is CoreEvent.Sized -> WireEvent.Sized(id.value, name, total)
        is CoreEvent.Progress -> WireEvent.Progress(id.value, done, total)
        is CoreEvent.Ended -> WireEvent.Ended(id.value, message)
        is CoreEvent.Abandoned -> WireEvent.Abandoned(id.value)
    }

internal class ListenerReporter(
    private val listener: Listener,
) : Reporter {
    override fun event(event: CoreEvent) = listener.event(event.toWire())
}

/** The core's questions put to [asker]. An answer of the wrong kind is no answer, as a decline. */
internal class AskerDecider(
    private val asker: Asker,
) : Decider {
    // An index the app made up is no answer at all, rather than a crash or a different binary than the one it meant.
    override fun chooseBinary(
        pkg: String,
        candidates: List<String>,
    ): Int? = (asker.ask(Question.ChooseBinary(pkg, candidates)) as? Answer.Index)?.index?.takeIf { it in candidates.indices }

    override fun stopProcesses(occupants: List<Occupant>) = confirmed(Question.StopProcesses(occupants.map { Holder(it.pid, it.path.toString()) }))

    override fun confirmRemoveLeftover(path: Path) = confirmed(Question.ConfirmRemoveLeftover(path.toString()))

    private fun confirmed(question: Question) = (asker.ask(question) as? Answer.Confirm)?.confirmed == true
}
