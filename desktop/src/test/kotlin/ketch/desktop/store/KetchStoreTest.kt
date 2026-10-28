@file:OptIn(ExperimentalCoroutinesApi::class)

package ketch.desktop.store

import ketch.desktop.core.Callbacks
import ketch.desktop.core.CancelToken
import ketch.desktop.core.Core
import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.CheckOutcome
import ketch.wire.InstallOptions
import ketch.wire.KetchError
import ketch.wire.Outcome
import ketch.wire.Stage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The store's flows the screens depend on, without a window. */
class KetchStoreTest {
    /** Sends each call to the fake [route] picks for it, after the pause it names. */
    private class Routed(
        val route: (Call) -> Pair<Core, Duration>,
    ) : Core {
        val calls = mutableListOf<Call>()
        override val version = "routed"

        override suspend fun call(
            call: Call,
            callbacks: Callbacks,
            cancel: CancelToken,
        ): JsonElement {
            calls += call
            val (core, pause) = route(call)
            delay(pause)
            return core.call(call, callbacks, cancel)
        }

        override fun close() = Unit
    }

    private fun busy(pid: Long?) = scenario("install-busy").copy(outcome = Outcome.Error(KetchError.Busy(pid)))

    @Test
    fun aRetryRunsTheRefusedInstallAgainOnceTheLockIsFree() =
        runTest {
            val core = FakeCore().script(busy(4242))
            val store = KetchStore(core)
            store.install("ripgrep")
            assertEquals(
                4242L,
                store.state.value.busy
                    ?.pid,
            )
            core.script(scenario("install-ok"))
            store.retryBusy()
            assertNull(store.state.value.busy)
            assertEquals(
                listOf("ripgrep"),
                store.state.value.installed
                    .map { it.name },
            )
        }

    @Test
    fun aLockThatStaysHeldBringsTheBannerBackWithTheNewHolder() =
        runTest {
            val core = FakeCore().script(busy(1))
            val store = KetchStore(core)
            store.install("ripgrep")
            core.script(busy(2))
            store.retryBusy()
            assertEquals(
                2L,
                store.state.value.busy
                    ?.pid,
            )
            assertEquals(2, core.calls.count { it is Call.Install })
        }

    @Test
    fun aRetryWithNothingRefusedDoesNothing() =
        runTest {
            val core = FakeCore()
            KetchStore(core).retryBusy()
            assertTrue(core.calls.isEmpty())
        }

    @Test
    fun aCancelWhileTheQuestionIsOpenDeclinesItAndStopsTheOperation() =
        runTest {
            val core = FakeCore().script(scenario("install-binary-choice"))
            val store = KetchStore(core)
            val running = launch { store.install("uv") }
            runCurrent()
            assertNotNull(store.state.value.pending)
            store.cancel()
            running.join()
            assertEquals(listOf<Answer>(Answer.Index(null)), core.answers.map { it.second })
            assertNull(store.state.value.pending)
            assertNull(store.state.value.error)
            assertTrue(
                store.state.value.installed
                    .isEmpty(),
            )
        }

    @Test
    fun aSecondOperationIsRefusedWhileOneRuns() =
        runTest {
            val core = FakeCore(stepDelay = 30.milliseconds).script(scenario("install-ok")).script(scenario("uninstall-ok"))
            val store = KetchStore(core)
            val running = launch { store.install("ripgrep") }
            runCurrent()
            assertTrue(store.state.value.isRunning)
            store.uninstall(listOf("fd"))
            assertEquals("Another operation is running. Wait for it or cancel it first.", store.state.value.error)
            store.dismissError()
            store.cancel()
            running.join()
            assertTrue(core.calls.none { it is Call.Uninstall })
            assertNull(store.state.value.error)
        }

    @Test
    fun aDownloadsProgressReachesThePackageItIsFor() =
        runTest {
            val step = 10.milliseconds
            val store = KetchStore(FakeCore(stepDelay = step).script(scenario("install-ok")))
            val running = launch { store.install("ripgrep") }
            val seen = mutableListOf<PackageProgress?>()
            while (running.isActive) {
                runCurrent()
                seen +=
                    store.state.value.activity
                        ?.packages
                        ?.get("ripgrep")
                advanceTimeBy(step)
            }
            assertTrue(PackageProgress(Stage.Downloading, 500_000, 2_000_000) in seen, seen.toString())
            assertTrue(PackageProgress(Stage.Installing, 2_000_000, 2_000_000) in seen, seen.toString())
            val fractions = seen.filterNotNull().map { it.fraction }
            assertEquals(fractions.sorted(), fractions, "the bar never runs backwards")
        }

    @Test
    fun theInstallOptionsCarryThePrereleaseChoice() =
        runTest {
            val core = FakeCore().script(scenario("install-ok"))
            KetchStore(core).install("ripgrep", prerelease = true)
            assertEquals(Call.Install(listOf("ripgrep"), InstallOptions(prerelease = true)), core.calls.first())
        }

    @Test
    fun aSlowerOlderSearchDoesNotReplaceANewerOne() =
        runTest {
            val browse = FakeCore().script(scenario("search-browse"))
            val grep = FakeCore().script(scenario("search"))
            val core = Routed { call -> if ((call as Call.Search).query.isEmpty()) browse to 600.milliseconds else grep to Duration.ZERO }
            val store = KetchStore(core)
            val older = launch { store.search("") }
            advanceTimeBy(100.milliseconds)
            store.search("grep")
            older.join()
            assertEquals(listOf("", "grep"), core.calls.map { (it as Call.Search).query })
            assertEquals(
                listOf("ripgrep", "ugrep"),
                store.state.value.search.known
                    .map { it.name },
            )
        }

    @Test
    fun aBurstOfKeystrokesSearchesOnceForTheLastText() =
        runTest {
            val core = FakeCore().script(scenario("search"))
            val store = KetchStore(core)
            val pause = 100.milliseconds
            val typed = listOf("g", "gr", "gre", "grep").map { text -> launch { store.searchAfterTyping(text, pause) } }
            typed.forEach { it.join() }
            assertEquals(listOf<Call>(Call.Search("grep", KetchStore.SEARCH_LIMIT)), core.calls)
            assertEquals(
                listOf("ripgrep", "ugrep"),
                store.state.value.search.known
                    .map { it.name },
            )
        }

    @Test
    fun aCancelledChangelogRequestGetsNoReplyAndNoAlert() =
        runTest {
            val inner = FakeCore().script(scenario("changelog-range"))
            val core = Routed { inner to 300.milliseconds }
            val store = KetchStore(core)
            var reply: List<*>? = null
            val request = launch { reply = store.changelog("ripgrep", "14.1.0", null) }
            advanceTimeBy(50.milliseconds)
            request.cancel()
            request.join()
            assertNull(reply)
            assertEquals(1, core.calls.size)
            assertNull(store.state.value.error)
        }

    @Test
    fun anUnreadableChangelogIsAnAlert() =
        runTest {
            val store = KetchStore(FakeCore())
            assertNull(store.changelog("ripgrep", null, null))
            assertEquals("no fake behaviour for changelog", store.state.value.error)
        }

    @Test
    fun theDoctorCountsTheChecksThatAreNotOk() =
        runTest {
            val store = KetchStore(FakeCore().script(scenario("doctor")))
            store.runDoctor()
            val checks = store.state.value.checks
            assertEquals(3, checks.size)
            assertEquals(2, store.state.value.problemCount)
            assertTrue(checks.any { it.outcome == CheckOutcome.Warn && it.fix != null })
        }

    @Test
    fun theLogKeepsTheNewestEntries() =
        runTest {
            val store = KetchStore(FakeCore().script(scenario("install-ok")))
            repeat(70) { store.install("ripgrep") }
            val log = store.state.value.log
            assertEquals(KetchStore.LOG_LIMIT, log.size)
            assertEquals(log.sortedBy { it.id }, log)
            assertEquals("Done: Installing ripgrep", log.last().message)
            assertTrue(log.first().id > 1)
        }

    @Test
    fun aDownloadsShareOfTheBarGrowsWithTheBytesReceived() {
        val half = PackageProgress(Stage.Downloading, 50, 100)
        val start = PackageProgress(Stage.Downloading, 0, 100)
        assertTrue(half.fraction > start.fraction)
        assertTrue(half.fraction < PackageProgress(Stage.Verifying).fraction)
        assertEquals(start.fraction, PackageProgress(Stage.Downloading, 50, null).fraction, "an unknown size fills nothing")
        assertEquals(0.0, Activity("idle").fraction)
        val two = Activity("two", mapOf("a" to PackageProgress(Stage.Resolving), "b" to PackageProgress(Stage.Verifying)))
        assertEquals((0.0 + 2.0 / Stage.entries.size) / 2, two.fraction)
    }
}
