package ketch.desktop.core

import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Event
import ketch.wire.KetchError
import ketch.wire.Outcome
import ketch.wire.Package
import ketch.wire.Question
import ketch.wire.ScriptStep
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeCoreTest {
    /** Records the events of a call and answers each question as the scenario's person did. */
    private class Scripted(
        val answers: List<Answer>,
    ) : Callbacks {
        val events = mutableListOf<Event>()
        private var next = 0

        override fun event(event: Event) {
            events += event
        }

        override suspend fun ask(question: Question): Answer = answers[next++]
    }

    private fun failure(body: suspend () -> Unit): KetchError = assertFailsWith<CoreException> { runBlocking { body() } }.error

    @Test
    fun everyScenarioPlaysBackAsTheCoreSentIt() {
        val all = scenarios()
        assertTrue(all.size >= 19, all.keys.toString())
        for ((name, scenario) in all) {
            assertEquals(name, scenario.name)
            val core = FakeCore().script(scenario)
            val callbacks = Scripted(scenario.script.filterIsInstance<ScriptStep.Ask>().map { it.answer })
            when (val outcome = scenario.outcome) {
                is Outcome.Ok -> assertEquals(outcome.value, runBlocking { core.call(scenario.call, callbacks) }, name)
                is Outcome.Error -> assertEquals(outcome.error, failure { core.call(scenario.call, callbacks) }, name)
            }
            assertEquals(scenario.script.filterIsInstance<ScriptStep.Reported>().map { it.event }, callbacks.events, name)
        }
    }

    @Test
    fun aCancelBetweenStepsStopsTheCallAndPlacesNothing() {
        val core = FakeCore().script(scenario("installed")).script(scenario("install-ok"))
        val before = runBlocking { core.value<List<Package>>(Call.InstalledPackages) }
        val cancel = CancelToken()
        val stopping =
            object : Callbacks {
                override fun event(event: Event) = cancel.cancel()
            }
        assertEquals(KetchError.Cancelled, failure { core.call(scenario("install-ok").call, stopping, cancel) })
        assertEquals(before, runBlocking { core.value<List<Package>>(Call.InstalledPackages) })
    }

    @Test
    fun installsAndUninstallsChangeWhatTheFakeHasInstalled() =
        runBlocking {
            val core = FakeCore().script(scenario("installed")).script(scenario("install-ok")).script(scenario("uninstall-ok"))
            val names = { runBlocking { core.value<List<Package>>(Call.InstalledPackages).map { it.name to it.version } } }
            assertEquals(listOf("fd", "ripgrep"), names().map { it.first }.sorted())
            core.call(scenario("install-ok").call)
            assertEquals(listOf("fd" to names().toMap().getValue("fd"), "ripgrep" to "14.1.1"), names().sortedBy { it.first })
            core.call(scenario("uninstall-ok").call)
            assertEquals(listOf("fd"), names().map { it.first })
        }

    @Test
    fun decliningTheBinaryChoiceStopsTheInstall() {
        val core = FakeCore().script(scenario("install-binary-choice"))
        assertEquals(KetchError.Cancelled, failure { core.call(scenario("install-binary-choice").call) })
        assertEquals(listOf(Answer.Index(null)), core.answers.map { it.second })
    }

    @Test
    fun aCallWithNoScenarioIsAnError() {
        assertEquals(KetchError.Other("no fake behaviour for doctor"), failure { FakeCore().call(Call.Doctor) })
    }

    @Test
    fun theErrorWordingIsTheRustApps() {
        assertEquals("ketch is running in another process (pid 4242).", describe(KetchError.Busy(4242)))
        assertEquals("ketch is running in another process.", describe(KetchError.Busy(null)))
        assertEquals("No package named nope.", describe(KetchError.NotFound("nope")))
        assertEquals("Network error: down", CoreException(KetchError.Network("down")).message)
    }

    @Test
    fun aTokenRunsItsHandlersOnceAndALateOneAtOnce() {
        val token = CancelToken()
        var runs = 0
        token.onCancel { runs++ }
        token.cancel()
        token.cancel()
        token.onCancel { runs++ }
        assertEquals(2, runs)
        assertTrue(token.isCancelled)
    }
}
