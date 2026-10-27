package ketch.core.shell

import fs.createDirAll
import fs.entryKind
import fs.mode
import fs.setMode
import ketch.core.config.Config
import ketch.core.model.Os
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.report.Report
import ketch.core.testing.read
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
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
            val mine = Path(Path(cfg.root, "share"), "mine.txt")
            mine.write("keep")
            uninstallCmdMacros(cfg)
            assertNull(entryKind(file))
            assertNull(entryKind(assertNotNull(file.parent)))
            assertNotNull(entryKind(mine), "a file ketch did not write was removed")
        }

    @Test
    fun noRegistryEntriesAreFoundOffWindows(): Unit =
        withTempDir { tmp ->
            assertEquals(emptyList(), registryEntries(Config.load(Path(tmp, "root"), Report.silent())))
        }

    @Test
    fun uninstallingAProfileKetchCreatedDeletesIt(): Unit =
        withTempDir { tmp ->
            val profile = Path(tmp, "profile.ps1")
            profile.write("$BEGIN\n. 'k.ps1'\n$END\n")
            assertTrue(uninstallPowershellProfile(profile))
            assertNull(entryKind(profile))
            assertFalse(uninstallPowershellProfile(profile))
        }

    /** A startup file the user made private must not come back readable, and a new one looks like any other file. */
    @Test
    fun aStartupFileKeepsItsModeAndANewOneGetsTheOrdinaryMode(): Unit =
        withTempDir { dir ->
            val private = Path(dir, ".zshrc")
            private.write("old")
            setMode(private, "600".toInt(8))
            write(private, "new")
            assertEquals("600".toInt(8), mode(private))

            val reference = Path(dir, "by-std")
            SystemFileSystem.sink(reference).buffered().use { it.writeString("x") }
            val fresh = Path(dir, "config/fish/config.fish")
            write(fresh, "new")
            assertEquals(mode(reference), mode(fresh))
        }

    /** A startup file that is a link into a dotfiles repository stays a link; the file behind it gets the block. */
    @Test
    fun installFollowsALinkedStartupFileAndUninstallRestoresIt(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            val dotfiles = Path(tmp, "dotfiles")
            createDirAll(home)
            createDirAll(dotfiles)
            val real = Path(dotfiles, "zshrc")
            real.write("# mine\n")
            fs.symlink(real, Path(home, ".zshrc"))
            withEnv(listOf("ZDOTDIR", "XDG_CONFIG_HOME"), mapOf("HOME" to home.toString(), "SHELL" to "/bin/zsh")) {
                val cfg = Config.load(Path(tmp, "root"), Report.silent())
                val change = install(cfg, Shell.Zsh, dryRun = false)
                assertEquals(Outcome.Added, change.outcome)
                assertEquals(fs.EntryKind.Symlink, entryKind(Path(home, ".zshrc")))
                assertTrue(cfg.binDir.toString() in real.read())
                assertEquals(listOf(Path(home, ".zshrc")), configuredIn(cfg))
                assertEquals(Outcome.Unchanged, install(cfg, Shell.Zsh, dryRun = false).outcome)
                assertEquals(Outcome.Removed, uninstall(Shell.Zsh, dryRun = false).outcome)
                assertEquals("# mine\n", real.read())
            }
        }

    /** A line the user wrote by hand already does the job; a second copy beside it would be impossible to tell apart. */
    @Test
    fun aHandWrittenLineIsLeftAloneAndADryRunWritesNothing(): Unit =
        withTempDir { tmp ->
            withEnv(listOf("ZDOTDIR", "XDG_CONFIG_HOME"), mapOf("HOME" to tmp.toString())) {
                val cfg = Config.load(Path(tmp, "root"), Report.silent())
                val fish = Path(tmp, ".config/fish/config.fish")
                assertEquals(Outcome.Added, install(cfg, Shell.Fish, dryRun = true).outcome)
                assertNull(entryKind(fish))
                createDirAll(assertNotNull(fish.parent))
                fish.write("set -gx PATH ${cfg.binDir} \$PATH\n")
                assertEquals(Outcome.Unchanged, install(cfg, Shell.Fish, dryRun = false).outcome)
                assertEquals(emptyList(), filesWithBlock())
            }
        }
}
