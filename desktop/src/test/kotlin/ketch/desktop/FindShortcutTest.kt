@file:OptIn(InternalComposeUiApi::class)

package ketch.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import ketch.desktop.ui.Look
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindShortcutTest {
    private val commandK = KeyEvent(Key.K, KeyEventType.KeyDown, isMetaPressed = true)

    @Test
    fun commandKAsksForTheSearchAndIsTakenByTheWindow() {
        val navigation = Navigation(Section.Installed)
        assertTrue(navigation.findOn(commandK))
        assertTrue(navigation.wantsSearch)
        assertEquals(Section.Installed, navigation.section)
    }

    @Test
    fun controlKIsTheWindowsLooksShortcut() {
        val navigation = Navigation(Section.Installed)
        assertTrue(navigation.findOn(KeyEvent(Key.K, KeyEventType.KeyDown, isCtrlPressed = true), Look.Fluent))
        assertTrue(navigation.wantsSearch)
    }

    @Test
    fun eachLookLeavesTheOtherLooksShortcut() {
        val navigation = Navigation()
        assertFalse(navigation.findOn(commandK, Look.Fluent))
        assertFalse(navigation.findOn(KeyEvent(Key.K, KeyEventType.KeyDown, isCtrlPressed = true)))
    }

    @Test
    fun aScreenWithoutASearchGoesToDiscover() {
        val navigation = Navigation(Section.Doctor)
        navigation.findOn(commandK)
        assertEquals(Section.Discover, navigation.section)
    }

    @Test
    fun anythingElseIsLeftForTheFocusedControl() {
        val navigation = Navigation()
        assertFalse(navigation.findOn(KeyEvent(Key.K, KeyEventType.KeyDown)))
        assertFalse(navigation.findOn(KeyEvent(Key.J, KeyEventType.KeyDown, isMetaPressed = true)))
        assertFalse(navigation.findOn(KeyEvent(Key.K, KeyEventType.KeyUp, isMetaPressed = true)))
        assertFalse(navigation.wantsSearch)
    }
}
