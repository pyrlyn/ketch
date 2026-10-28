package ketch.desktop.store

import ketch.desktop.core.FakeCore
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.KeyValues
import ketch.desktop.settings.MemoryKeyValues
import ketch.wire.Call
import ketch.wire.Outcome
import ketch.wire.Scenario
import ketch.wire.Upgrade
import ketch.wire.Wire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private fun upgrade(
    name: String,
    installed: String = "1.0.0",
    latest: String = "1.1.0",
    pinned: Boolean = false,
    heldBy: String? = null,
) = Upgrade(name, installed, latest, "v$latest", pinned, heldBy)

/** The rule for which upgrades are news, and what the notice says. */
class UpdateNoticesTest {
    @Test
    fun aNewUpgradeIsNews() = assertEquals(listOf("rg"), UpdateNotices.fresh(listOf(upgrade("rg")), emptySet()).map { it.name })

    @Test
    fun anAnnouncedUpgradeIsNot() = assertTrue(UpdateNotices.fresh(listOf(upgrade("rg")), setOf("rg@1.1.0")).isEmpty())

    @Test
    fun aNewerReleaseOfAnAnnouncedPackageIsNewsAgain() = assertEquals(1, UpdateNotices.fresh(listOf(upgrade("rg", latest = "1.2.0")), setOf("rg@1.1.0")).size)

    @Test
    fun aHeldUpgradeNeverCounts() = assertTrue(UpdateNotices.fresh(listOf(upgrade("fd", pinned = true), upgrade("bat", heldBy = "ketch.lock")), emptySet()).isEmpty())

    @Test
    fun atMostThreeAreListedAndTheRestCounted() {
        val (title, body) = UpdateNotices.message(listOf("a", "b", "c", "d", "e").map { upgrade(it) })
        assertEquals("5 updates available", title)
        assertEquals("a 1.0.0 → 1.1.0\nb 1.0.0 → 1.1.0\nc 1.0.0 → 1.1.0\nand 2 more", body)
    }

    @Test
    fun oneUpdateIsSingular() = assertEquals("1 update available" to "rg 1.0.0 → 1.1.0", UpdateNotices.message(listOf(upgrade("rg"))))

    @Test
    fun controlAndBidiCharactersAreDropped() {
        // A release tag is the project author's text: an escape or a right-to-left override must not reach the notice.
        val (_, body) = UpdateNotices.message(listOf(upgrade("rg", installed = "1", latest = "2\u001b[31m‮x")))
        assertEquals("rg 1 → 2[31mx", body)
    }
}

/** The checks against the store: one notice per new batch, asked for only when turned on. */
class UpdateChecksTest {
    private class Recorder(
        private val allows: Boolean = true,
    ) : UpdateNotifier {
        val posted = mutableListOf<Pair<String, String>>()
        var asked = 0

        override suspend fun requestAuthorization(): Boolean {
            asked++
            return allows
        }

        override fun post(
            title: String,
            body: String,
        ) {
            posted += title to body
        }
    }

    private fun outdated(vararg upgrades: Upgrade) =
        Scenario(
            "outdated",
            "what is outdated",
            Call.Outdated,
            emptyList(),
            Outcome.Ok(Wire.json.encodeToJsonElement(ListSerializer(Upgrade.serializer()), upgrades.toList())),
        )

    private fun checks(
        core: FakeCore,
        notifier: UpdateNotifier,
        values: KeyValues = MemoryKeyValues(),
    ): Triple<UpdateChecks, KetchStore, AppSettings> {
        val store = KetchStore(core)
        val settings = AppSettings(values)
        return Triple(UpdateChecks(store, settings, notifier), store, settings)
    }

    @Test
    fun severalUpgradesMakeOneNotice() =
        runTest {
            val notifier = Recorder()
            val (checks, store) = checks(FakeCore().script(outdated(upgrade("rg"), upgrade("fd"))), notifier)
            checks.setNotifications(true)
            store.refresh()
            checks.notifyOfNewUpdates()
            assertEquals(listOf("2 updates available" to "rg 1.0.0 → 1.1.0\nfd 1.0.0 → 1.1.0"), notifier.posted)
        }

    @Test
    fun theSameUpgradesAreNotAnnouncedTwice() =
        runTest {
            val notifier = Recorder()
            val (checks, store) = checks(FakeCore().script(outdated(upgrade("rg"))), notifier)
            checks.setNotifications(true)
            store.refresh()
            checks.notifyOfNewUpdates()
            store.refresh()
            checks.notifyOfNewUpdates()
            assertEquals(1, notifier.posted.size)
        }

    @Test
    fun nothingIsPostedWhileNoticesAreOff() =
        runTest {
            val notifier = Recorder()
            val (checks, store) = checks(FakeCore().script(outdated(upgrade("rg"))), notifier)
            store.refresh()
            checks.notifyOfNewUpdates()
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun heldUpgradesAreLeftOut() =
        runTest {
            val notifier = Recorder()
            val (checks, store) = checks(FakeCore().script(outdated(upgrade("rg"), upgrade("fd", pinned = true))), notifier)
            checks.setNotifications(true)
            store.refresh()
            checks.notifyOfNewUpdates()
            assertEquals(listOf("1 update available" to "rg 1.0.0 → 1.1.0"), notifier.posted)
        }

    @Test
    fun permissionIsAskedOnlyWhenNoticesGoOn() =
        runTest {
            val notifier = Recorder()
            val (checks, _, settings) = checks(FakeCore(), notifier)
            assertNull(checks.setNotifications(true))
            assertTrue(settings.state.value.notifies)
            assertNull(checks.setNotifications(false))
            assertFalse(settings.state.value.notifies)
            assertEquals(1, notifier.asked)
        }

    @Test
    fun aRefusalLeavesNoticesOffAndSaysWhy() =
        runTest {
            val (checks, _, settings) = checks(FakeCore(), Recorder(allows = false))
            assertNotNull(checks.setNotifications(true))
            assertFalse(settings.state.value.notifies)
        }

    @Test
    fun announcedUpgradesSurviveARestart() =
        runTest {
            val values = MemoryKeyValues()
            val core = FakeCore().script(outdated(upgrade("rg")))
            val first = Recorder()
            val (before, store) = checks(core, first, values)
            before.setNotifications(true)
            store.refresh()
            before.notifyOfNewUpdates()
            val second = Recorder()
            val (after, again) = checks(core, second, values)
            again.refresh()
            after.notifyOfNewUpdates()
            assertEquals(1, first.posted.size)
            assertTrue(second.posted.isEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thePeriodicCheckRunsNowThenEveryIntervalAndRestartsOnAChange() =
        runTest {
            val core = FakeCore()
            val (checks, _, settings) = checks(core, Recorder())

            fun reads() = core.calls.count { it is Call.Outdated }
            val job = launch { checks.run() }
            runCurrent()
            assertEquals(1, reads())
            advanceTimeBy(59.minutes)
            assertEquals(1, reads())
            advanceTimeBy(2.minutes)
            assertEquals(2, reads())
            // A new interval checks at once, then on the new beat.
            settings.update { it.copy(intervalMinutes = 15) }
            runCurrent()
            assertEquals(3, reads())
            advanceTimeBy(16.minutes)
            assertEquals(4, reads())
            job.cancel()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thePeriodicCheckWaitsOutARunningOperation() =
        runTest {
            val core = FakeCore(stepDelay = 120.minutes).script(ketch.desktop.core.scenario("install-ok"))
            val (checks, store) = checks(core, Recorder())
            val install = launch { store.install("ripgrep") }
            runCurrent()
            assertTrue(store.state.value.isRunning)
            val before = core.calls.count { it is Call.Outdated }
            val job = launch { checks.run() }
            runCurrent()
            assertEquals(before, core.calls.count { it is Call.Outdated })
            job.cancel()
            install.cancel()
        }
}
