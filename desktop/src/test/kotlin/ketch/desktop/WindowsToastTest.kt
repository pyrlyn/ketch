package ketch.desktop

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsToastTest {
    @Test
    fun theNoticeCarriesTheUpdateButton() {
        val xml = toastXml("2 updates available", "rg 1.0.0 → 1.1.0")
        assertContains(xml, "<text>2 updates available</text><text>rg 1.0.0 → 1.1.0</text>")
        assertContains(xml, "<action content=\"Update\" arguments=\"update\" activationType=\"foreground\"/>")
        assertContains(xml, "launch=\"open\"")
    }

    @Test
    fun aNameFromARegistryCannotBreakOutOfTheText() {
        val xml = toastXml("x", "a</text><action arguments='update'/>&")
        assertContains(xml, "a&lt;/text&gt;&lt;action arguments=&apos;update&apos;/&gt;&amp;")
        assertEquals(1, Regex("<action ").findAll(xml).count())
    }

    @Test
    fun aToastHoldsTwoLinesOfBody() {
        val xml = toastXml("t", "one\n\ntwo\nthree")
        assertContains(xml, "<text>one</text><text>two</text>")
        assertFalse("three" in xml)
    }
}
