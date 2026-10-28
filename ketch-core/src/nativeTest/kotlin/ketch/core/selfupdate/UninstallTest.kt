@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.selfupdate

import fs.createDir
import fs.createDirAll
import fs.entryKind
import ketch.core.config.Config
import ketch.core.report.Ctx
import ketch.core.report.Event
import ketch.core.report.Recorder
import ketch.core.report.Report
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal fun cfg(root: Path) = Config.load(root, Report.silent())

internal fun Config.removeRoot(
    home: Path,
    finish: (Path, List<Path>) -> Boolean = { _, _ -> false },
) = removeRootAt(Ctx(this, Report.silent()), root, home, finish)

class UninstallTest {
    @Test
    fun uninstallDoesNotWipeBinWhenTheRootIsHome(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            createDirAll(home)
            val cfg = cfg(home)
            createDirAll(cfg.binDir)
            Path(cfg.binDir, "keep-me").write("stay")
            createDirAll(cfg.storeDir)
            Path(cfg.storeDir, "mine").write("also")

            cfg.removeRoot(home)

            assertEquals("stay", Path(cfg.binDir, "keep-me").read())
            assertEquals("also", Path(cfg.storeDir, "mine").read())
        }

    @Test
    fun uninstallKeepsForeignFilesUnderARootKetchDidNotCreate(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            createDirAll(home)
            // `KETCH_ROOT=/usr/local`: a shared prefix that existed before ketch.
            val cfg = cfg(Path(tmp, "usr-local"))
            createDirAll(cfg.binDir)
            Path(cfg.binDir, "someone-elses").write("stay")
            createDirAll(cfg.cacheDir)
            Path(cfg.cacheDir, "theirs").write("stay")

            cfg.removeRoot(home)

            assertEquals("stay", Path(cfg.binDir, "someone-elses").read())
            assertEquals("stay", Path(cfg.cacheDir, "theirs").read())
        }

    @Test
    fun uninstallWipesTheDefaultRootWithoutAMarker(): Unit =
        withTempDir { tmp ->
            // Roots made before the marker existed: `~/.ketch` is ketch's by name.
            val home = Path(tmp, "home")
            val root = Path(home, ".ketch")
            val cfg = cfg(root)
            createDirAll(cfg.binDir)
            Path(cfg.binDir, "gone").write("x")

            cfg.removeRoot(home) { _, _ -> fail("nothing was left to finish later") }

            assertNull(entryKind(root), "the default root goes whole")
        }

    @Test
    fun sameDirFoldsCaseAndTrailingSeparators(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "Home")
            createDirAll(home)
            assertTrue(sameDir(Path(tmp, "HOME"), home))
            assertTrue(sameDir(Path("$home/"), home))
            assertTrue(containsDir(tmp, home))
            assertFalse(containsDir(home, tmp))
        }

    @Test
    fun uninstallWipesADedicatedRoot(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            createDirAll(home)
            val root = Path(tmp, ".ketch")
            val cfg = cfg(root)
            createDirAll(cfg.binDir)
            Path(cfg.binDir, "gone").write("x")
            cfg.markRoot()

            cfg.removeRoot(home) { _, _ -> fail("nothing was left to finish later") }

            assertNull(entryKind(cfg.binDir), "dedicated bin dir should be gone")
            assertNull(entryKind(root), "an emptied root goes too")
        }

    @Test
    fun aRootThatIsHomeIsNeverHandedOn(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            val cfg = cfg(home)
            createDirAll(cfg.storeDir)
            Path(cfg.storeDir, "mine").write("x")

            cfg.removeRoot(home) { _, _ -> fail("the home directory must never be scheduled for removal") }
        }

    /** Stands in for Windows refusing to delete the running `ketch.exe`: a directory is something `removeFile` rejects on every OS. */
    @Test
    fun uninstallWarnsAndFinishesWhenTheRunningBinaryCannotBeRemoved(): Unit =
        withTempDir { tmp ->
            val cfg = cfg(Path(tmp, ".ketch"))
            createDirAll(cfg.root)
            val shell = Path(tmp, ".bashrc")
            shell.write("# kept\n# >>> ketch >>>\nexport PATH=\"/tmp/ketch/bin:\$PATH\"\n# <<< ketch <<<\n")
            val exe = Path(tmp, "ketch.exe")
            createDir(exe)

            val plan = UninstallPlan(shellFiles = listOf(shell), exe = exe)
            val recorder = Recorder()
            val removed = uninstallSelf(Ctx(cfg, Report(recorder)), plan)

            assertTrue(shell in removed, "the shell file was not reported removed: $removed")
            assertFalse(exe in removed, "the binary was reported removed: $removed")
            val warned = recorder.events().filterIsInstance<Event.Warn>().any { exe.toString() in it.detail }
            assertTrue(warned, "no warning named the binary: ${recorder.events()}")
        }
}
