package ketch.serve

import ketch.core.cancel.Cancel
import ketch.core.error.KetchError
import ketch.core.process.Occupant
import ketch.core.report.Report
import ketch.core.report.Stage
import ketch.wire.Answer
import ketch.wire.Event
import ketch.wire.Question
import ketch.wire.TaskKind
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ketch.core.report.Event as CoreEvent
import ketch.wire.Stage as WireStage

class CallbacksTest {
    private class Recorded : Listener {
        val events = mutableListOf<Event>()

        override fun event(event: Event) {
            events += event
        }
    }

    @Test
    fun coreEventsReachTheListenerWithTheirIds() {
        val recorded = Recorded()
        val core = Report(ListenerReporter(recorded))
        core.warn("careful")
        val id = core.activity("unpacking").use { it.id.value }
        assertEquals(
            listOf(Event.Warn("careful"), Event.Began(id, TaskKind.Activity("unpacking")), Event.Ended(id, null)),
            recorded.events,
        )
    }

    @Test
    fun stepsAndProgressCrossWithTheirFields() {
        val recorded = Recorded()
        val core = Report(ListenerReporter(recorded))
        core.event(CoreEvent.Step("rg", Stage.Verifying))
        ListenerReporter(recorded).event(CoreEvent.Progress(core.activity("x").id, 3, 9))
        assertEquals(Event.Step("rg", WireStage.Verifying), recorded.events[0])
        assertTrue(recorded.events.any { it is Event.Progress && it.done == 3L && it.total == 9L })
    }

    /** Picks [index] and confirms when [yes], only for the holders and leftover the tests name. */
    private class Scripted(
        val index: Int?,
        val yes: Boolean,
    ) : Asker {
        override fun ask(question: Question): Answer =
            when (question) {
                is Question.ChooseBinary -> Answer.Index(index)
                is Question.StopProcesses -> Answer.Confirm(yes && question.holders.all { it.path == "/k/bin/rg" })
                is Question.ConfirmRemoveLeftover -> Answer.Confirm(yes && question.path == "/k/store/ghost")
            }
    }

    @Test
    fun theDeciderPicksByIndexAndRejectsOneOutOfRange() {
        val candidates = listOf("rg-a", "rg-b")
        assertEquals(1, AskerDecider(Scripted(1, false)).chooseBinary("rg", candidates))
        assertNull(AskerDecider(Scripted(2, false)).chooseBinary("rg", candidates))
        assertNull(AskerDecider(Scripted(-1, false)).chooseBinary("rg", candidates))
        assertNull(AskerDecider(Scripted(null, false)).chooseBinary("rg", candidates))
    }

    @Test
    fun theDeciderSeesTheHoldersItIsAskedAbout() {
        val occupants = listOf(Occupant(7, Path("/k/bin/rg")))
        assertTrue(AskerDecider(Scripted(null, true)).stopProcesses(occupants))
        assertFalse(AskerDecider(Scripted(null, false)).stopProcesses(occupants))
    }

    @Test
    fun theDeciderSeesTheLeftoverItIsAskedAbout() {
        val leftover = Path("/k/store/ghost")
        assertTrue(AskerDecider(Scripted(null, true)).confirmRemoveLeftover(leftover))
        assertFalse(AskerDecider(Scripted(null, false)).confirmRemoveLeftover(leftover))
    }

    @Test
    fun anAnswerOfTheWrongKindIsADecline() {
        val confused = Asker { Answer.Index(0) }
        assertFalse(AskerDecider(confused).stopProcesses(listOf(Occupant(7, Path("/k/bin/rg")))))
        assertNull(AskerDecider { Answer.Confirm(true) }.chooseBinary("rg", listOf("a")))
    }

    @Test
    fun theCancelAHostKeepsIsTheOneTheRequestChecks() {
        val cancel = Cancel()
        val req =
            installRequest(
                ketch.core.config.Config
                    .load(null, Report.silent()),
                ketch.wire.InstallOptions(),
                "o/r",
                cancel,
            )
        req.cancel.check()
        cancel.cancel()
        assertTrue(req.cancel.isCancelled)
        assertFailsWith<KetchError.Cancelled> { req.cancel.check() }
    }
}
