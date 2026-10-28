package ketch.ui

import icons.ICON_WIDTH
import icons.OPERATION_ICONS
import icons.displayWidth
import ketch.core.error.KetchError
import snapshot.SnapshotSettings
import snapshot.assertSnapshot
import snapshot.withSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOSTILE = "evil\u001b[2K\u001b[1;31mFAKE\u001b[0m"
private const val FILTERED = "evil[2K[1;31mFAKE[0m"

class UiTest {
    // Native tests run on one thread, so the settings need no lock between them; each test starts from its own.
    private fun initTest(
        color: Boolean?,
        quiet: Boolean,
        verbose: Boolean,
    ) {
        init(color, quiet, verbose)
        emojiOn = false
    }

    @AfterTest
    fun reset() = initTest(false, quiet = false, verbose = false)

    private fun assertClientTextIsFiltered(line: String) {
        assertFalse('\u001b' in line, line)
        assertTrue(FILTERED in line, line)
    }

    private fun snapshot(
        name: String,
        shown: String,
    ) = withSettings(SnapshotSettings(prefix = "ketch__ui__tests", source = "src/ui.rs")) {
        assertSnapshot(name, shown, expression = "shown")
    }

    @Test
    fun stepDetailCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        assertClientTextIsFiltered(stepLine("fetch", HOSTILE))
    }

    @Test
    fun successDetailCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        assertClientTextIsFiltered(successLine("done", HOSTILE))
    }

    @Test
    fun aWarningCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        assertClientTextIsFiltered(warnLine(HOSTILE))
    }

    @Test
    fun aNoteCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        assertClientTextIsFiltered(noteLine(HOSTILE))
    }

    @Test
    fun debugOutputCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = true)
        assertClientTextIsFiltered(debugLine(HOSTILE))
    }

    @Test
    fun anErrorHeadlineCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        val err = KetchError.Msg(HOSTILE)
        assertClientTextIsFiltered(errorLines(err.message, emptyList(), null)[0])
    }

    @Test
    fun errorDetailsCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        val err = KetchError.Command("test", "1", HOSTILE)
        val lines = errorLines(err.message, err.details(), null)
        assertEquals(2, lines.size)
        assertClientTextIsFiltered(lines[1])
    }

    @Test
    fun anErrorHintCannotRedrawTheTerminal() {
        initTest(false, quiet = false, verbose = false)
        val err = KetchError.UnknownScheme(HOSTILE)
        val hint = checkNotNull(err.hint()) { "UnknownScheme carries a hint" }
        val lines = errorLines(err.message, emptyList(), hint)
        assertEquals(2, lines.size)
        assertClientTextIsFiltered(lines[1])
    }

    /** One of each line kind a terminal can get, in the order a run shows them. */
    private fun everyLineKind(): List<String> =
        listOf(
            stepLine("installing", "ripgrep 14.1.0"),
            successLine("installed", "ripgrep 14.1.0"),
            warnLine("ripgrep ships no checksum"),
            noteLine("ripgrep is pinned"),
            debugLine("GET /repos/BurntSushi/ripgrep"),
        ) + errorLines("`ghost` is not installed", listOf("looked in state.json"), "run `ketch list`") +
            listOf(
                confirmLine("remove ripgrep?", false),
                cancelledLine("remove ripgrep?"),
                promptLine(Tone.Hint, "answer", "package name?"),
            )

    @Test
    fun eachLineKindIsPaintedByItsMeaning() {
        initTest(true, quiet = false, verbose = true)
        snapshot("each_line_kind_is_painted_by_its_meaning", everyLineKind().joinToString("\n").replace("\u001b", "\\e"))
    }

    @Test
    fun eachLineKindCarriesItsIconWhenEmojiAreOn() {
        initTest(false, quiet = false, verbose = true)
        emojiOn = true
        val shown = everyLineKind().joinToString("\n")
        emojiOn = false
        snapshot("each_line_kind_carries_its_icon_when_emoji_are_on", shown)
    }

    @Test
    fun noLineKindCarriesAnIconWhenEmojiAreOff() {
        initTest(false, quiet = false, verbose = true)
        val shown = everyLineKind().joinToString("\n")
        snapshot("no_line_kind_carries_an_icon_when_emoji_are_off", shown)
        for ((_, icon) in OPERATION_ICONS) assertFalse(icon in shown, shown)
    }

    @Test
    fun everyIconFillsTheSameGutter() {
        for ((_, icon) in OPERATION_ICONS) {
            assertEquals(ICON_WIDTH, displayWidth(icon), icon)
            assertEquals(1, icon.codePointCount(), "$icon needs a selector")
        }
        for (kind in listOf(Tone.Success, Tone.Warning, Tone.Error, Tone.Note)) {
            val icon = icon("", kind).orEmpty()
            assertEquals(ICON_WIDTH, displayWidth(icon), icon)
            assertEquals(1, icon.codePointCount(), "$icon needs a selector")
        }
    }

    private fun String.codePointCount() = count { !it.isLowSurrogate() }

    @Test
    fun textAfterTheVerbStartsInOneColumnWithOrWithoutAnIcon() {
        initTest(false, quiet = false, verbose = true)
        emojiOn = true
        val lines =
            listOf(
                stepLine("installing", "ripgrep"),
                stepLine("resolving", "ripgrep"),
                successLine("up to date", "ripgrep"),
                warnLine("ripgrep"),
                debugLine("ripgrep"),
                errorLines("x", listOf("ripgrep"), null)[1],
            )
        emojiOn = false
        for (line in lines) {
            val at = line.indexOf("ripgrep").coerceAtLeast(0)
            assertEquals(14, displayWidth(line.substring(0, at)), line)
        }
    }

    @Test
    fun anOperationIconOutranksTheMeaningIcon() {
        assertEquals("📦", icon("installed", Tone.Success))
        assertEquals("🧹", icon("uninstalled", Tone.Success))
        assertEquals("⏪", icon("rolled back", Tone.Success))
        assertEquals("✅", icon("up to date", Tone.Success))
        assertNull(icon("resolving", Tone.Step))
    }

    private data class EmojiCase(
        val wanted: Boolean,
        val terminal: Boolean,
        val term: String?,
        val shown: Boolean,
    )

    @Test
    fun emojiShowOnlyWhenWantedOnACapableTerminal() {
        val cases =
            listOf(
                EmojiCase(wanted = true, terminal = true, term = "xterm-256color", shown = true),
                EmojiCase(wanted = true, terminal = true, term = null, shown = true),
                // Turned off by the config, the environment or the flag.
                EmojiCase(wanted = false, terminal = true, term = "xterm", shown = false),
                EmojiCase(wanted = true, terminal = false, term = "xterm", shown = false),
                EmojiCase(wanted = true, terminal = true, term = "dumb", shown = false),
            )
        for (case in cases) assertEquals(case.shown, emojiAllowed(case.wanted, case.terminal, case.term), case.toString())
    }

    @Test
    fun noLineKindCarriesEscapeBytesWithColourOff() {
        initTest(false, quiet = false, verbose = true)
        for (line in everyLineKind()) assertFalse('\u001b' in line, line)
    }
}
