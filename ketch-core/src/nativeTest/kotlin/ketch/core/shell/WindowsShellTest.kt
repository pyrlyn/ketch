package ketch.core.shell

import fs.createDirAll
import ketch.core.error.KetchError
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DOSKEY = "doskey /macrofile=\"C:\\Users\\u\\.ketch\\share\\ketch\\ketch.doskey\""

private val WINDOWS_BIN = Path("C:\\Users\\u\\.ketch\\bin")

class WindowsShellTest {
    @Test
    fun autorunAddAppendsOnceAfterWhatIsThere() {
        val cases =
            listOf(
                null to AutoRunEdit.Set(DOSKEY),
                "" to AutoRunEdit.Set(DOSKEY),
                "  " to AutoRunEdit.Set(DOSKEY),
                "@echo off" to AutoRunEdit.Set("@echo off & $DOSKEY"),
                DOSKEY to AutoRunEdit.Unchanged,
                "@echo off & $DOSKEY" to AutoRunEdit.Unchanged,
                "$DOSKEY & cls" to AutoRunEdit.Unchanged,
                "a & $DOSKEY & b" to AutoRunEdit.Unchanged,
            )
        for ((current, expected) in cases) assertEquals(expected, autorunAdd(current, DOSKEY), "$current")
    }

    @Test
    fun autorunRemoveTakesExactlyKetchsPart() {
        val cases =
            listOf(
                DOSKEY to AutoRunEdit.Delete,
                "@echo off & $DOSKEY" to AutoRunEdit.Set("@echo off"),
                "$DOSKEY & cls" to AutoRunEdit.Set("cls"),
                "a & $DOSKEY & b" to AutoRunEdit.Set("a & b"),
                "@echo off" to AutoRunEdit.Unchanged,
                "" to AutoRunEdit.Unchanged,
                // Another root's line is not this one's to take.
                "doskey /macrofile=\"D:\\other\\share\\ketch\\ketch.doskey\"" to AutoRunEdit.Unchanged,
            )
        for ((current, expected) in cases) assertEquals(expected, autorunRemove(current, DOSKEY), current)
    }

    /** Add then remove hands back the value that was there, byte for byte. */
    @Test
    fun autorunAddThenRemoveRestoresTheEarlierValue() {
        for (earlier in listOf("@echo off", "set X=1 & prompt \$g ", "%USERPROFILE%\\init.cmd")) {
            val with = assertIs<AutoRunEdit.Set>(autorunAdd(earlier, DOSKEY), "nothing added to $earlier")
            assertEquals(AutoRunEdit.Set(earlier), autorunRemove(with.value, DOSKEY))
        }
    }

    @Test
    fun autorunCommandQuotesThePathAndRefusesWhatCmdCannotHold() {
        assertEquals(DOSKEY, autorunCommand(Path("C:\\Users\\u\\.ketch\\share\\ketch\\ketch.doskey")))
        assertFailsWith<KetchError> { autorunCommand(Path("C:\\a%PATH%\\ketch.doskey")) }
        assertFailsWith<KetchError> { autorunCommand(Path("C:\\a\"b\\ketch.doskey")) }
    }

    @Test
    fun theProfileBlockDotSourcesTheScriptOnlyWhenItExists() {
        val script = "'C:\\Users\\u\\Documents\\PowerShell\\Completions\\ketch.ps1'"
        assertEquals(
            "$BEGIN\nif (Test-Path -LiteralPath $script) { . $script }\n$END\n",
            powershellBlock("C:\\Users\\u\\Documents\\PowerShell\\Completions\\ketch.ps1"),
        )
    }

    @Test
    fun powershellQuotingKeepsQuotesAndDollarsLiteral() {
        assertEquals("'o''brien'", quotePowershell("o'brien"))
        assertEquals("'o\u2019\u2019brien'", quotePowershell("o\u2019brien"))
        assertEquals("'\$env:X'", quotePowershell("\$env:X"))
    }

    @Test
    fun aProfileGetsTheBlockOnceAndLosesItByteForByte() {
        val original = "Set-PSReadLineOption -EditMode Emacs\r\n"
        val script = "C:\\d\\PowerShell\\Completions\\ketch.ps1"
        val with = assertNotNull(spliceProfile(original, script), "added")
        assertNull(spliceProfile(with, script))
        assertEquals(ProfileEdit.Rewrite(original), unspliceProfile(with))
    }

    @Test
    fun aProfileKetchCreatedGoesAwayWhole() {
        val with = assertNotNull(spliceProfile("", "C:\\d\\ketch.ps1"), "added")
        assertTrue(with.startsWith(BEGIN))
        assertEquals(ProfileEdit.Delete, unspliceProfile(with))
    }

    /** Windows PowerShell reads a BOM-less profile in the ANSI code page. */
    @Test
    fun aNewProfileNamingANonAsciiPathStartsWithABom() {
        val with = assertNotNull(spliceProfile("", "C:\\Users\\Иван\\Documents\\ketch.ps1"), "added")
        assertTrue(with.startsWith(BOM))
        assertEquals(ProfileEdit.Delete, unspliceProfile(with))
    }

    @Test
    fun profilesAreTheAllHostsOnesForBothEditions() {
        val docs = Path("D")
        assertEquals(Path(Path(docs, "PowerShell"), "profile.ps1"), PowerShell.Core.profile(docs))
        assertEquals(Path(Path(docs, "WindowsPowerShell"), "profile.ps1"), PowerShell.Desktop.profile(docs))
    }

    @Test
    fun onlyAPolicyThatRunsLocalScriptsGetsANewProfile() {
        val cases = listOf("RemoteSigned\r\n" to true, "Unrestricted" to true, "Bypass" to true, "Restricted" to false, "AllSigned" to false)
        for ((policy, runs) in cases) assertEquals(runs, runsLocalScripts(policy), policy)
    }

    @Test
    fun windowsPathPrependIsIdempotentAndSlashInsensitive() {
        val added = assertNotNull(windowsPathPrepend("C:\\Windows\\System32", WINDOWS_BIN))
        assertTrue(added.startsWith("C:\\Users\\u\\.ketch\\bin;"), added)
        assertNull(windowsPathPrepend(added, WINDOWS_BIN))
        assertTrue(windowsPathHas(added, Path("C:/Users/u/.ketch/bin/")))
    }

    @Test
    fun windowsPathHasMatchesAQuotedRegistryEntry() {
        val path = "C:\\Windows\\System32;\"C:\\Users\\u\\.ketch\\bin\";C:\\Windows"
        assertTrue(windowsPathHas(path, WINDOWS_BIN))
        assertNull(windowsPathPrepend(path, WINDOWS_BIN))
        assertEquals("C:\\Windows\\System32;C:\\Windows", windowsPathRemove(path, WINDOWS_BIN))
    }

    /** A folder name may contain `;`. Quotes keep it one PATH entry; splitting on every `;` would miss the match. */
    @Test
    fun windowsPathKeepsASemicolonInsideQuotes() {
        val dir = Path("C:\\weird;name")
        val path = "C:\\Windows;\"C:\\weird;name\";C:\\Other"
        assertEquals(listOf("C:\\Windows", "\"C:\\weird;name\"", "C:\\Other"), windowsPathEntryList(path))
        assertTrue(windowsPathHas(path, dir))
        assertNull(windowsPathPrepend(path, dir))
        assertEquals("C:\\Windows;C:\\Other", windowsPathRemove(path, dir))
    }

    @Test
    fun windowsPathRemoveDropsOnlyTheNamedEntry() {
        assertEquals("C:\\Windows\\System32;C:\\Windows", windowsPathRemove("C:\\Windows\\System32;C:\\Users\\u\\.ketch\\bin;C:\\Windows", WINDOWS_BIN))
        assertNull(windowsPathRemove("C:\\Windows\\System32", WINDOWS_BIN))
    }

    @Test
    fun aPathEntryForAMissingFolderMatchesOnlyBySpelling(): Unit =
        withTempDir { tmp ->
            val gone = Path(tmp, "gone")
            assertFalse(windowsPathHas(tmp.toString(), gone))
            assertTrue(windowsPathHas(gone.toString(), gone))
        }

    @Test
    fun onlyKetchBinDirsThatAreGoneAreStale(): Unit =
        withTempDir { tmp ->
            val live = Path(Path(tmp, ".ketch"), "bin")
            createDirAll(live)
            val goneDefault = "C:\\Users\\u\\.ketch\\bin"
            val goneCustom = Path(Path(tmp, "custom"), "bin")
            val path = "C:\\Windows;$live;$goneDefault;$goneCustom;C:\\gone\\tools\\bin"
            assertEquals(listOf(goneDefault, goneCustom.toString()), stalePathEntries(path, goneCustom))
        }

    @Test
    fun percentVarsExpandLikeARegExpandSzValue() {
        val lookup = { name: String -> "C:\\Users\\Zoë".takeIf { name == "USERPROFILE" } }
        assertEquals("C:\\Users\\Zoë\\.ketch\\bin", expandPercentVars("%USERPROFILE%\\.ketch\\bin", lookup))
        // Unknown names, an empty `%%` and a lone `%` stay as written.
        assertEquals("%JAVA_HOME%\\bin;50%%;C:\\Users\\Zoë;a%b", expandPercentVars("%JAVA_HOME%\\bin;50%%;%USERPROFILE%;a%b", lookup))
    }

    @Test
    fun aBinDirWrittenWithAVariableIsAlreadyOnThePath(): Unit =
        withTempDir { tmp ->
            withEnv(emptyList(), mapOf("KETCH_TEST_B95_ROOT" to tmp.toString())) {
                val dir = Path(tmp, "bin")
                val path = "%SystemRoot%\\system32;%KETCH_TEST_B95_ROOT%\\bin;%JAVA_HOME%\\bin"
                assertTrue(windowsPathHas(path, dir))
                assertNull(windowsPathPrepend(path, dir))
                // What stays is written back exactly as it was read.
                assertEquals("%SystemRoot%\\system32;%JAVA_HOME%\\bin", windowsPathRemove(path, dir))
            }
        }

    @Test
    fun theUserPathKeepsItsRegistryKind() {
        assertEquals("String", userPathKind("String"))
        assertEquals("ExpandString", userPathKind("ExpandString"))
        assertEquals("ExpandString", userPathKind(null))
        assertEquals("ExpandString", userPathKind("Binary"))
    }
}
