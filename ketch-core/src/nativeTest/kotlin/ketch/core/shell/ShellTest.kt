package ketch.core.shell

import fs.createDirAll
import fs.entryKind
import fs.joinPath
import ketch.core.config.Config
import ketch.core.model.Os
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.report.Report
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {
    @Test
    fun bashPrefersAFileTheHostActuallyReads() {
        val first =
            Shell.Bash
                .candidates(Path("/home/u"))
                .first()
                .name
        assertEquals(if (TargetSpec.host().os == Os.MacOs) ".bash_profile" else ".bashrc", first)
    }

    @Test
    fun theDoskeyFileHoldsTheFourMacros() {
        val lines = DOSKEY_MACROS.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        assertEquals(listOf("ki=ketch install $*", "ku=ketch upgrade $*", "kl=ketch list $*", "kun=ketch uninstall $*"), lines)
        assertTrue(DOSKEY_MACROS.endsWith("\r\n"))
    }

    @Test
    fun uninstallingCmdMacrosRemovesTheFileAndEmptyDirsOnly(): Unit =
        withTempDir { tmp ->
            val cfg = Config.load(tmp, Report.silent())
            val file = doskeyFile(cfg)
            createDirAll(assertNotNull(file.parent))
            file.write(DOSKEY_MACROS)
            val mine = joinPath(joinPath(cfg.root, "share"), "mine.txt")
            mine.write("keep")
            uninstallCmdMacros(cfg)
            assertNull(entryKind(file))
            assertNull(entryKind(assertNotNull(file.parent)))
            assertNotNull(entryKind(mine), "a file ketch did not write was removed")
        }

    @Test
    fun uninstallingAProfileKetchCreatedDeletesIt(): Unit =
        withTempDir { tmp ->
            val profile = joinPath(tmp, "profile.ps1")
            profile.write("$BEGIN\n. 'k.ps1'\n$END\n")
            assertTrue(uninstallPowershellProfile(profile))
            assertNull(entryKind(profile))
            assertFalse(uninstallPowershellProfile(profile))
        }

    /** A line the user wrote by hand already does the job; a second copy beside it would be impossible to tell apart. */
    @Test
    fun aHandWrittenLineIsLeftAloneAndADryRunWritesNothing(): Unit =
        withTempDir { tmp ->
            // Windows takes the home directory from USERPROFILE.
            withEnv(listOf("ZDOTDIR", "XDG_CONFIG_HOME"), mapOf("HOME" to tmp.toString(), "USERPROFILE" to tmp.toString())) {
                val cfg = Config.load(joinPath(tmp, "root"), Report.silent())
                val fish = joinPath(tmp, ".config/fish/config.fish")
                assertEquals(Outcome.Added, install(cfg, Shell.Fish, dryRun = true).outcome)
                assertNull(entryKind(fish))
                createDirAll(assertNotNull(fish.parent))
                fish.write("set -gx PATH ${nativeSpelling(cfg.binDir)} \$PATH\n")
                assertEquals(Outcome.Unchanged, install(cfg, Shell.Fish, dryRun = false).outcome)
                assertEquals(emptyList(), filesWithBlock())
            }
        }
}
