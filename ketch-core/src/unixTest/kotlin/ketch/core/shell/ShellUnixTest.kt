package ketch.core.shell

import fs.createDirAll
import fs.entryKind
import fs.mode
import fs.setMode
import fs.symlink
import ketch.core.config.Config
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
import kotlin.test.assertTrue

class ShellUnixTest {
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

    @Test
    fun noRegistryEntriesAreFoundOffWindows(): Unit =
        withTempDir { tmp ->
            assertEquals(emptyList(), registryEntries(Config.load(Path(tmp, "root"), Report.silent())))
        }

    // The Windows case is an 8.3 short name; a link is how the same folder gets a second spelling on a unix host.
    @Test
    fun aPathEntryThatResolvesToTheBinDirIsTheBinDir(): Unit =
        withTempDir { tmp ->
            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val link = Path(tmp, "alias")
            symlink(bin, link)
            val path = "/usr/bin;$link"
            assertTrue(windowsPathHas(path, bin))
            assertEquals("/usr/bin", windowsPathRemove(path, bin))
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
}
