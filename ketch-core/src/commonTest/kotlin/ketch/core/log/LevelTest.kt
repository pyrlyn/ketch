package ketch.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelTest {
    @Test
    fun timestampsMatchDatesEverybodyKnows() {
        assertEquals("1970-01-01T00:00:00Z", timestamp(0))
        assertEquals("1970-01-01T23:59:59Z", timestamp(86_399))
        assertEquals("1970-01-02T00:00:00Z", timestamp(86_400))
        assertEquals("2001-09-09T01:46:40Z", timestamp(1_000_000_000))
        assertEquals("2023-11-14T22:13:20Z", timestamp(1_700_000_000))
    }

    @Test
    fun leapDaysLandOnThe29th() {
        // 2000 is the century that is a leap year, and the one every naive rule gets wrong.
        assertEquals("2024-02-29T00:00:00Z", timestamp(1_709_164_800))
        assertEquals("2000-02-29T00:00:00Z", timestamp(951_782_400))
        assertEquals("2100-03-01T00:00:00Z", timestamp(4_107_542_400))
    }

    @Test
    fun aRecordIsAlwaysOneLine() {
        val flattened = escape("first\nsecond\ttabbed\u001b[2Kcleared")
        assertEquals("first\\nsecond\\ttabbed[2Kcleared", flattened)
        assertFalse('\n' in flattened)
    }

    @Test
    fun aLevelAdmitsItselfAndEverythingMoreSerious() {
        assertTrue(Level.Warn.passes(Level.Info))
        assertTrue(Level.Error.passes(Level.Info))
        assertTrue(Level.Info.passes(Level.Info))
        assertFalse(Level.Debug.passes(Level.Info))
        assertFalse(Level.Warn.passes(Level.Error))
        assertTrue(Level.Debug.passes(Level.Debug))
    }

    @Test
    fun offWritesNothingAtAll() {
        for (level in listOf(Level.Error, Level.Warn, Level.Info, Level.Debug)) {
            assertFalse(level.passes(Level.Off), "$level escaped an off log")
        }
    }

    @Test
    fun levelsAndFormatsParseTheNamesPeopleType() {
        assertEquals(Level.Warn, Level.parse("WARNING"))
        assertEquals(Level.Off, Level.parse("off"))
        assertEquals(Level.Debug, Level.parse(" Debug "))
        assertFailsWith<IllegalArgumentException> { Level.parse("chatty") }
        assertEquals(Format.Json, Format.parse("ndjson"))
        assertEquals(Format.Text, Format.parse("text"))
        assertFailsWith<IllegalArgumentException> { Format.parse("xml") }
    }
}
