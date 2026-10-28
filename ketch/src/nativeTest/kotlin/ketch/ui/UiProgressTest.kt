package ketch.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOSTILE = "evil\u001b[2K\u001b[1;31mFAKE\u001b[0m"
private const val FILTERED = "evil[2K[1;31mFAKE[0m"

// The tables and bars of ui.rs's tests; UiTest has its lines.
class UiProgressTest {
    @kotlin.test.AfterTest
    fun reset() = init(false, quiet = false, verbose = false)

    private fun assertClientTextIsFiltered(line: String) {
        assertFalse('\u001b' in line, line)
        assertTrue(FILTERED in line, line)
    }

    @Test
    fun aTableRowStaysOnOneLineWhenACellHasNewlinesOrTabs() {
        val lines = tableLines(listOf("package", "description"), listOf(listOf("pkg", "first\nsecond\ttabbed")))
        assertEquals(2, lines.size)
        assertFalse('\n' in lines[1], lines[1])
        assertFalse('\t' in lines[1], lines[1])
        assertEquals("package".length + 2, lines[1].indexOf("first second tabbed"), lines[1])
    }

    @Test
    fun aTableCellCannotRedrawTheTerminalOrShiftItsColumns() {
        val lines = tableLines(listOf("asset", "score"), listOf(listOf("$HOSTILE.tar.gz", "12")))
        assertEquals(2, lines.size)
        assertFalse('\u001b' in lines[1], lines[1])
        assertTrue(lines[1].startsWith("$FILTERED.tar.gz"), lines[1])
        // The padding is the filtered length: counted with the sequences, the next column would start 15 characters
        // too far right.
        assertEquals("$FILTERED.tar.gz".length + 2, lines[1].indexOf("12"))
    }

    @Test
    fun paintedCellsKeepTheirColumnsAligned() {
        init(false, quiet = false, verbose = false)
        val rows = listOf(listOf("a", "x"), listOf("longer", "y"))
        val lines = styledTableLines(listOf("name", "v"), rows) { _, c, t -> if (c == 0) "\u001b[1m$t\u001b[0m" else t }
        assertTrue(lines[1].startsWith("\u001b[1ma\u001b[0m"), lines[1])
        val plain = lines.map { it.replace("\u001b[1m", "").replace("\u001b[0m", "") }
        assertEquals(plain[1].indexOf('x'), plain[2].indexOf('y'), plain.toString())
        assertEquals("longer".length + 2, plain[1].indexOf('x'))
    }

    @Test
    fun aDownloadBarLabelCannotRedrawTheTerminal() {
        val bar = BarProgress()
        bar.start(10, HOSTILE)
        assertClientTextIsFiltered(bar.bar.message)

        val named = BarProgress()
        named.label = HOSTILE
        named.start(10, "asset")
        assertClientTextIsFiltered(named.bar.message)
    }

    @Test
    fun aKnownTotalIsABar() {
        for (total in listOf(0L, 4L)) {
            activity("packages", total).use {
                assertEquals(ActivityKind.Bar, it.kind)
                assertEquals(total, it.bar.length)
            }
        }
    }

    @Test
    fun anUnknownTotalIsASpinner() {
        activity("resolving", null).use {
            assertEquals(ActivityKind.Spinner, it.kind)
            assertNull(it.bar.length)
        }
    }

    @Test
    fun aBarAdvancesWhenWorkAdvances() {
        activity("packages", 3).use {
            it.inc(2)
            assertEquals(2, it.bar.position)
            it.setPosition(3)
            assertEquals(3, it.bar.position)
        }
    }

    @Test
    fun anActivityMessageCannotRedrawTheTerminal() {
        activity(HOSTILE, null).use { assertClientTextIsFiltered(it.bar.message) }
    }

    @Test
    fun aReplacedActivityMessageCannotRedrawTheTerminal() {
        activity("packages", 1).use {
            it.setMessage(HOSTILE)
            assertClientTextIsFiltered(it.bar.message)
        }
    }
}
