@file:OptIn(ExperimentalCoroutinesApi::class)

package ketch.desktop.store

import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Holder
import ketch.wire.Question
import ketch.wire.Stage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The store driven by the contract scenarios: what the real core sends is what the app's flows are tested against. */
class ContractScenarioTest {
    private fun store(vararg names: String): Pair<KetchStore, FakeCore> {
        val core = FakeCore()
        names.forEach { core.script(scenario(it)) }
        return KetchStore(core) to core
    }

    private val KetchStore.messages get() = state.value.log.map { it.message }

    @Test
    fun readsReturnTheRecordsTheCoreSent() =
        runTest {
            val (store, _) = store("installed", "outdated", "doctor")
            store.refresh()
            store.runDoctor()
            val state = store.state.value
            assertEquals(listOf("fd", "ripgrep"), state.installed.map { it.name })
            assertEquals("github:BurntSushi/ripgrep", state.installed.first { it.name == "ripgrep" }.source)
            assertEquals(listOf("ripgrep"), state.updates.map { it.name })
            assertEquals(listOf("fd"), state.held.map { it.name })
            assertEquals("/work/app/ketch.lock", state.held.first().heldBy)
            assertEquals(1, state.pendingUpgradeCount)
            assertEquals("14.1.1", state.outdatedVersion("ripgrep"))
            assertNull(state.outdatedVersion("fd"))
            assertEquals(2, state.problemCount)
            assertNotNull(state.lastChecked)
        }

    @Test
    fun anEmptySearchBrowsesTheRegistryTheCoreSent() =
        runTest {
            val (store, core) = store("search-browse")
            store.search("")
            assertEquals(
                listOf("fd", "ripgrep", "uv"),
                store.state.value.search.known
                    .map { it.name },
            )
            assertEquals(listOf<Call>(Call.Search("", KetchStore.SEARCH_LIMIT)), core.calls)
            assertNull(store.state.value.error)
        }

    @Test
    fun theChangelogRangeIsOneSectionPerRelease() =
        runTest {
            val (store, _) = store("changelog-range")
            val sections = assertNotNull(store.changelog("ripgrep", "14.1.0", null))
            assertEquals(listOf("14.1.1", "14.1.0"), sections.map { it.version })
            assertTrue("Fix a regression in `--json`." in sections[0].body)
            assertEquals("Performance improvements.", sections[1].body)
        }

    @Test
    fun aCleanInstallWalksEveryStageAndRecordsThePackage() =
        runTest {
            val (store, core) = store("install-ok")
            store.install("ripgrep")
            val state = store.state.value
            assertNull(state.error)
            assertNull(state.activity)
            assertEquals(listOf(scenario("install-ok").call), core.calls.filterIsInstance<Call.Install>())
            assertEquals(listOf("ripgrep" to "14.1.1"), state.installed.map { it.name to it.version })
            val messages = store.messages
            for (stage in Stage.entries - Stage.Trusting) assertTrue("ripgrep: ${stage.name.lowercase()}" in messages, "missing $stage")
            // The core's events land before its result.
            assertTrue(messages.indexOf("installed ripgrep 14.1.1") in 0..<messages.indexOf("Done: Installing ripgrep"), messages.toString())
            assertEquals(LogEntry(state.log.last().id, state.log.last().at, LogLevel.Success, "Done: Installing ripgrep"), state.log.last())
        }

    @Test
    fun aHeldLockOffersRetryWithTheHoldersPid() =
        runTest {
            val (store, _) = store("install-busy")
            store.install("ripgrep")
            val state = store.state.value
            assertEquals(4242L, state.busy?.pid)
            assertNull(state.error)
            assertTrue(state.installed.isEmpty())
            assertEquals("ketch is running in another process (pid 4242).", state.log.last { it.level == LogLevel.Warning }.message)
        }

    @Test
    fun aLockWithNoKnownHolderStillOffersRetry() =
        runTest {
            val (store, _) = store("install-busy-unknown-holder")
            store.install("ripgrep")
            val busy = assertNotNull(store.state.value.busy)
            assertNull(busy.pid)
            assertEquals("ketch is running in another process.", busy.message)
            assertNull(store.state.value.error)
        }

    @Test
    fun cancelledIsLoggedNotShownAsAnAlert() =
        runTest {
            val (store, _) = store("install-cancelled")
            store.install("ripgrep")
            val state = store.state.value
            assertNull(state.error)
            assertNull(state.busy)
            assertTrue(state.log.any { it.level == LogLevel.Warning && it.message == "Cancelled" })
            assertTrue(state.installed.isEmpty())
        }

    @Test
    fun aNetworkFailureShowsTheCoresWording() =
        runTest {
            val (store, _) = store("install-network-failure")
            store.install("ripgrep")
            assertTrue(
                store.state.value.error
                    .orEmpty()
                    .startsWith("Network error: HTTP 503 from https://github.com/"),
                store.state.value.error,
            )
            assertTrue(
                store.state.value.installed
                    .isEmpty(),
            )
            assertEquals(
                LogLevel.Error,
                store.state.value.log
                    .last()
                    .level,
            )
        }

    @Test
    fun aVerificationFailureShowsTheCoresWording() =
        runTest {
            val (store, _) = store("install-verification-failure")
            store.install("ripgrep")
            assertTrue(
                store.state.value.error
                    .orEmpty()
                    .startsWith("Verification failed: checksum mismatch"),
                store.state.value.error,
            )
        }

    @Test
    fun notFoundNamesThePackageAndAnyOtherErrorIsTheCoresMessage() =
        runTest {
            val (store, _) = store("install-not-found")
            store.install("nope")
            assertEquals("No package named nope.", store.state.value.error)
            val (other, _) = store("install-other-error")
            other.install("local:/tmp/missing")
            assertEquals("`/tmp/missing` does not exist", other.state.value.error)
        }

    @Test
    fun aBinaryChoiceIsPutToTheDialogAndTheAnswerLinksThatBinary() =
        runTest {
            val (store, core) = store("install-binary-choice")
            val running = launch { store.install("uv") }
            runCurrent()
            val question = Question.ChooseBinary("uv", listOf("uv", "uvx"))
            assertEquals(
                question,
                store.state.value.pending
                    ?.question,
            )
            store.answer(Answer.Index(1))
            running.join()
            assertEquals(listOf<Pair<Question, Answer>>(question to Answer.Index(1)), core.answers)
            assertNull(store.state.value.pending)
            assertEquals(
                listOf("uv"),
                store.state.value.installed
                    .map { it.name },
            )
            assertNull(store.state.value.error)
        }

    @Test
    fun decliningTheBinaryChoiceStopsTheInstall() =
        runTest {
            val (store, core) = store("install-binary-choice")
            val running = launch { store.install("uv") }
            runCurrent()
            store.decline()
            running.join()
            assertEquals(listOf<Answer>(Answer.Index(null)), core.answers.map { it.second })
            assertTrue(
                store.state.value.installed
                    .isEmpty(),
            )
            assertNull(store.state.value.error)
        }

    @Test
    fun theProcessesQuestionWaitsForAnAnswerFromTheDialog() =
        runTest {
            val (store, _) = store("installed", "upgrade-stops-processes")
            store.refresh()
            val running = launch { store.upgrade(listOf("ripgrep")) }
            runCurrent()
            assertEquals(
                Question.StopProcesses(listOf(Holder(5150, "/Users/me/.ketch/bin/rg"))),
                store.state.value.pending
                    ?.question,
            )
            store.answer(Answer.Confirm(true))
            running.join()
            assertNull(store.state.value.error)
            assertEquals(
                "14.1.1",
                store.state.value.installed
                    .single { it.name == "ripgrep" }
                    .version,
            )
            assertTrue("Done: Upgrading ripgrep" in store.messages)
        }

    @Test
    fun anUpgradeWithNothingToDoLeavesThingsAsTheyWere() =
        runTest {
            val (store, core) = store("installed", "upgrade-nothing-to-do")
            store.refresh()
            val before = store.state.value.installed
            store.upgrade()
            assertEquals(before, store.state.value.installed)
            assertNull(store.state.value.error)
            assertEquals(listOf(Call.Upgrade(emptyList())), core.calls.filterIsInstance<Call.Upgrade>())
            assertTrue("Done: Upgrading all packages" in store.messages)
        }

    @Test
    fun anUninstallRemovesTheRecord() =
        runTest {
            val (store, _) = store("installed", "uninstall-ok")
            store.refresh()
            store.uninstall(listOf("ripgrep"))
            assertEquals(
                listOf("fd"),
                store.state.value.installed
                    .map { it.name },
            )
        }

    @Test
    fun uninstallingAnUnknownNameShowsTheError() =
        runTest {
            val (store, _) = store("uninstall-not-found")
            store.uninstall(listOf("nope"))
            assertEquals("No package named nope.", store.state.value.error)
        }

    @Test
    fun aCancelBetweenReplayedStepsStopsTheInstall() =
        runTest {
            val core = FakeCore(stepDelay = 30.milliseconds).script(scenario("install-ok"))
            val store = KetchStore(core)
            val running = launch { store.install("ripgrep") }
            runCurrent()
            assertEquals(
                PackageProgress(Stage.Resolving),
                store.state.value.activity
                    ?.packages
                    ?.get("ripgrep"),
            )
            store.cancel()
            assertEquals(
                true,
                store.state.value.activity
                    ?.isCancelling,
            )
            running.join()
            val state = store.state.value
            assertTrue(state.installed.isEmpty())
            assertNull(state.error)
            assertNull(state.activity)
            assertEquals("Cancelled", state.log.last { it.level == LogLevel.Warning }.message)
        }
}
