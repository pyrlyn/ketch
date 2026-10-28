package ketch.desktop.ui

import ketch.desktop.core.FakeCore
import ketch.desktop.core.scenario
import ketch.desktop.store.Activity
import ketch.desktop.store.KetchStore
import ketch.desktop.store.StoreState
import ketch.wire.Upgrade
import kotlinx.coroutines.test.runTest
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tray menu as the menu-bar panel has it, read from the store's state. */
class TrayTest {
    private suspend fun loaded(): StoreState {
        val store = KetchStore(FakeCore().script(scenario("installed")).script(scenario("outdated")))
        store.refresh()
        return store.state.value
    }

    private fun List<TrayEntry>.item(label: String) = filterIsInstance<TrayEntry.Item>().single { it.label == label }

    @Test
    fun theMenuCountsAndListsWhatIsPending() =
        runTest {
            val state = loaded()
            val menu = trayMenu(state)
            // fd is held, so only ripgrep is listed and counted.
            assertEquals("1 update · ${state.installed.size} installed", (menu.first() as TrayEntry.Item).label)
            assertEquals(TrayAction.Update("ripgrep"), menu.item("Update ripgrep 14.1.0 → 14.1.1").action)
            assertTrue(menu.item("Update all…").enabled)
            assertEquals(
                listOf("Open Ketch", "Check for updates", "Settings…", "Quit Ketch"),
                menu.filterIsInstance<TrayEntry.Item>().takeLast(4).map { it.label },
            )
        }

    @Test
    fun nothingPendingLeavesUpdateAllOff() {
        val menu = trayMenu(StoreState())
        assertEquals("0 updates · 0 installed", (menu.first() as TrayEntry.Item).label)
        assertFalse(menu.item("Update all…").enabled)
    }

    @Test
    fun aRunningOperationShowsItsProgressAndCancel() =
        runTest {
            val state = loaded().copy(activity = Activity("Installing bat"))
            val menu = trayMenu(state)
            assertTrue(TrayEntry.Item("Installing bat · 0%") in menu)
            assertTrue(menu.item("Cancel").enabled)
            // While one runs, nothing else may start.
            assertFalse(menu.item("Update ripgrep 14.1.0 → 14.1.1").enabled)
            assertFalse(menu.item("Update all…").enabled)
            assertFalse(menu.item("Check for updates").enabled)
            assertFalse(trayMenu(state.copy(activity = Activity("Installing bat", isCancelling = true))).item("Cancel").enabled)
        }

    @Test
    fun atMostFiveUpdatesAreListed() {
        val outdated = (1..8).map { Upgrade("p$it", "1", "2", "v2", pinned = false, heldBy = null) }
        val rows = trayMenu(StoreState(outdated = outdated)).filterIsInstance<TrayEntry.Item>().filter { it.action is TrayAction.Update }
        assertEquals(TRAY_UPDATE_ROWS, rows.size)
    }

    @Test
    fun theTooltipSaysWhatTheIconShows() =
        runTest {
            assertEquals("Ketch", trayTooltip(StoreState()))
            assertEquals("Ketch, 1 update available, working", trayTooltip(loaded().copy(activity = Activity("Installing bat"))))
        }

    @Test
    fun theIconIsDrawnAtTheSizeAsked() {
        assertEquals(16, trayImage(0, Color.BLUE, size = 16).width)
        assertEquals(32, trayImage(12, Color.BLUE).height)
    }
}
