package ketch.core.binchoice

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class BinChoiceTest {
    private val never: (List<String>) -> Int? = { fail("the prompt must not be reached") }

    private fun remembered(vararg list: String) = Known(remembered = list.toList())

    private fun flag(name: String) = Known(flag = name)

    private fun error(block: () -> Unit) = assertFailsWith<KetchError>(block = block).message

    @Test
    fun theFlagWinsOverTheExactNameAndARememberedChoice() = assertEquals(1 to Picked.Flag, choose("rtok", listOf("rtok", "rtok-hook"), Known("rtok-hook", listOf("rtok")), never, "m.toml"))

    @Test
    fun theFlagIgnoresCaseAndTheExeExtension() = assertEquals(0 to Picked.Flag, choose("rtok", listOf("rtok-cli.exe", "rtok-hook.exe"), flag("RTOK-CLI"), never, "m.toml"))

    @Test
    fun aFlagNamingNoCandidateIsAnErrorListingThem() {
        val err = error { choose("rtok", listOf("rtok-cli", "rtok-hook"), flag("rtok-typo"), never, "m.toml") }
        assertTrue(err.contains("--bin `rtok-typo`"), err)
        assertTrue(err.contains("rtok-cli, rtok-hook"), err)
    }

    @Test
    fun stateIsTrustedBeforeTheLockfile() {
        val found = listOf("rtok-cli", "rtok-hook")
        assertEquals(1 to Picked.Remembered, choose("rtok", found, remembered("rtok-hook", "rtok-cli"), never, "m.toml"))
        assertEquals(0 to Picked.Remembered, choose("rtok", found, remembered("rtok-gone", "rtok-cli"), never, "m.toml"))
    }

    @Test
    fun aFlagWithNothingToChooseMustStillNameABinary() {
        val found = listOf("rg", "rg-completions")
        checkFlag("RG.exe", found)
        val err = error { checkFlag("ripgrep", found) }
        assertTrue(err.contains("rg, rg-completions"), err)
    }

    @Test
    fun theExactNameWinsInWindowsSortOrder() = assertEquals(1 to Picked.Exact, choose("rtok", listOf("rtok-hook.exe", "rtok.exe"), Known(), never, "m.toml"))

    @Test
    fun theExactNameWinsInUnixSortOrder() = assertEquals(0 to Picked.Exact, choose("rtok", listOf("rtok", "rtok-hook"), Known(), never, "m.toml"))

    @Test
    fun theExactNameIgnoresCaseAndTheExeExtension() = assertEquals(1 to Picked.Exact, choose("rtok", listOf("Rtok-Hook.EXE", "RTOK.exe"), Known(), never, "m.toml"))

    @Test
    fun theExactNameBeatsARememberedChoice() = assertEquals(0 to Picked.Exact, choose("rtok", listOf("rtok", "rtok-hook"), remembered("rtok-hook"), never, "m.toml"))

    @Test
    fun aRememberedChoiceIsReusedWithoutAsking() = assertEquals(0 to Picked.Remembered, choose("rtok", listOf("rtok-cli.exe", "rtok-hook.exe"), remembered("rtok-cli"), never, "m.toml"))

    @Test
    fun aRememberedChoiceThatIsGoneFallsThroughToAsking() {
        val found = listOf("rtok-cli", "rtok-hook")
        var offered = emptyList<String>()
        val picked =
            choose("rtok", found, remembered("rtok-old"), {
                offered = it
                1
            }, "m.toml")
        assertEquals(1 to Picked.Asked, picked)
        assertEquals(found, offered)
    }

    @Test
    fun anAnswerOutOfRangeIsNotAChoice() {
        error { choose("rtok", listOf("rtok-cli", "rtok-hook"), Known(), { 7 }, "m.toml") }
    }

    @Test
    fun withoutAPromptTheErrorListsCandidatesAndHowToSetBin() {
        val err = error { choose("rtok", listOf("rtok-cli", "rtok-hook"), Known(), { null }, "~/m/rtok.toml") }
        assertTrue(err.contains("rtok-cli"), err)
        assertTrue(err.contains("rtok-hook"), err)
        assertTrue(err.contains("bin = [{ name = \"rtok-cli\" }]"), err)
        assertTrue(err.contains("~/m/rtok.toml"), err)
    }

    @Test
    fun contendersNeedTwoFilesSharingThePackageName() {
        assertTrue(contenders("go", listOf("go", "gofmt")).isEmpty())
        assertTrue(contenders("ripgrep", listOf("rg")).isEmpty())
        assertEquals(listOf(1, 2), contenders("rtok", listOf("helper", "rtok", "rtok-hook")))
    }

    @Test
    fun stemsDropOnlyAWindowsRunExtension() {
        assertEquals("rtok", stem("rtok.EXE"))
        assertEquals("tool", stem("tool.cmd"))
        assertEquals("tool.sh", stem("tool.sh"))
        assertEquals(".exe", stem(".exe"))
        assertEquals("Rtok-Cli", commandName("Rtok-Cli.EXE"))
        assertEquals("rtok", commandName("rtok"))
        assertEquals("rtok.cmd", commandName("rtok.cmd"))
    }
}
