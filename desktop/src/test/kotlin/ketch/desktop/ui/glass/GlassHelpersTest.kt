package ketch.desktop.ui.glass

import androidx.compose.ui.graphics.Color
import ketch.desktop.settings.Prefs
import ketch.desktop.store.LogEntry
import ketch.desktop.store.LogLevel
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.Tokens
import ketch.desktop.ui.firstLine
import ketch.desktop.ui.linkedTo
import ketch.desktop.ui.logDays
import ketch.desktop.ui.logText
import ketch.desktop.ui.tilde
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassHelpersTest {
    private val tokens = Tokens.brand

    private fun entry(
        id: Long,
        at: String,
        level: LogLevel = LogLevel.Info,
    ) = LogEntry(id, Instant.parse(at), level, "line $id")

    @Test
    fun aPathUnderHomeReadsWithATilde() {
        assertEquals("~/.ketch/bin/uv", tilde("/Users/me/.ketch/bin/uv", "/Users/me"))
        assertEquals("~", tilde("/Users/me", "/Users/me"))
        assertEquals("/Users/menace/x", tilde("/Users/menace/x", "/Users/me"))
    }

    @Test
    fun theLinksNameTheFirstInFullAndTheRestByName() {
        assertEquals("~/bin/uv, uvx", linkedTo(listOf("/home/bin/uv", "/home/bin/uvx"), "/home"))
        assertNull(linkedTo(emptyList(), "/home"))
    }

    @Test
    fun anUpdateQuotesTheFirstChangeOfTheChangelog() {
        assertEquals("Fix a regression.", firstLine("# 14.1.1\n\nBug fixes:\n\n- Fix a regression.\n- Another."))
        assertEquals("Performance improvements.", firstLine("Performance improvements."))
        assertEquals("Fix --json.", firstLine("- Fix `--json`."))
        assertNull(firstLine("# Only a heading\n\n"))
    }

    @Test
    fun theLogGroupsByDayNewestFirst() {
        val days =
            logDays(
                listOf(entry(1, "2026-10-01T09:00:00Z"), entry(2, "2026-10-29T09:00:00Z"), entry(3, "2026-10-30T09:00:00Z"), entry(4, "2026-10-30T10:00:00Z")),
                LocalDate.parse("2026-10-30"),
                ZoneOffset.UTC,
            )
        assertEquals(listOf("Today", "Yesterday", "1 October 2026"), days.map { it.first })
        assertEquals(listOf(4L, 3L), days.first().second.map { it.id })
    }

    @Test
    fun theExportedLogReadsOldestFirst() {
        val text = logText(listOf(entry(1, "2026-10-30T09:00:00Z"), entry(2, "2026-10-30T10:30:00Z", LogLevel.Error)), ZoneOffset.UTC)
        assertEquals("2026-10-30 09:00:00 INFO line 1\n2026-10-30 10:30:00 ERROR line 2\n", text)
    }

    @Test
    fun aStoredColourIsAPresetOrRrggbbAndNothingElse() {
        assertEquals(Color(0xFF112233), rgb("#112233"))
        assertNull(rgb("112233"))
        assertNull(rgb("#1122"))
        assertNull(rgb("#gggggg"))
    }

    @Test
    fun theChoicesResolveToColours() {
        val choices = resolve(Prefs(tint = "mint", glass = Prefs.CLEAR_GLASS, accent = "#102030"), tokens, Appearance.Light)
        assertEquals(tokens.macColor("preset.tint.mint", Appearance.Light), choices.tint)
        assertEquals(Color(0xFF102030), choices.accent)
        assertTrue(choices.clear)
    }

    @Test
    fun theStandardChoicesAddNothing() {
        val choices = resolve(Prefs(), tokens, Appearance.Light)
        assertNull(choices.tint)
        assertNull(choices.accent)
        assertFalse(choices.clear)
        assertEquals(1f, choices.wash)
    }

    @Test
    fun anUnknownNameIsTheStandardChoice() = assertNull(resolve(Prefs(tint = "chartreuse"), tokens, Appearance.Dark).tint)

    @Test
    fun aLightAccentTakesDarkText() {
        val light = glassColors(tokens, Appearance.Light, GlassChoices(accent = Color(0xFFFFE066)))
        val dark = glassColors(tokens, Appearance.Light, GlassChoices(accent = Color(0xFF102030)))
        assertEquals(Color(0xFF0D1B2A), light.accentOn)
        assertEquals(tokens.color("accent.on", Appearance.Light), dark.accentOn)
    }

    @Test
    fun everyAppearanceHasItsColours() {
        for (appearance in Appearance.entries) {
            val colors = glassColors(tokens, appearance, GlassChoices())
            assertEquals(appearance.isDark, colors.dark)
        }
    }
}
