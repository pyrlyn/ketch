@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.selfupdate

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.setMode
import fs.symlink
import ketch.core.error.KetchError
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.state.Lock
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.mkfifo
import platform.posix.open
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private const val READ_ONLY = 0b101_101_101
private const val WRITABLE = 0b111_101_101

class UninstallUnixTest {
    @Test
    fun uninstallDoesNotWipeHomeReachedThroughASymlink(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            createDirAll(home)
            val link = Path(tmp, "home-link")
            symlink(home, link)
            val cfg = cfg(link)
            // Even a marker does not make the home directory ketch's to empty.
            cfg.markRoot()
            createDirAll(cfg.binDir)
            Path(cfg.binDir, "keep-me").write("stay")

            cfg.removeRoot(home)

            assertEquals(EntryKind.File, entryKind(Path(Path(home, "bin"), "keep-me")))
        }

    /** Stands in for Windows refusing to delete the running `ketch.exe`: a read-only package folder keeps its file, and so the store and the root. */
    @Test
    fun whatCannotBeRemovedNowIsHandedOnToFinishLater(): Unit =
        withTempDir { tmp ->
            val home = Path(tmp, "home")
            val cfg = cfg(Path(tmp, ".ketch"))
            val locked = Path(cfg.storeDir, SELF_NAME)
            createDirAll(locked)
            Path(locked, "ketch").write("running")
            createDirAll(cfg.binDir)
            cfg.markRoot()
            setMode(locked, READ_ONLY)

            var handed: Pair<Path, List<Path>>? = null
            try {
                cfg.removeRoot(home) { root, left ->
                    handed = root to left
                    true
                }
            } finally {
                setMode(locked, WRITABLE)
            }

            val (root, left) = assertNotNull(handed, "the leftovers were handed on")
            assertEquals(cfg.root, root)
            assertEquals(listOf(cfg.storeDir), left)
            assertNull(entryKind(cfg.binDir), "everything else went now")
        }

    /**
     * A shell startup file opened as a fifo blocks in `read` until the test writes it. That is the moment the lock must
     * still be held, and the lock file must still be on disk: the root has not been deleted yet.
     */
    @Test
    fun uninstallHoldsTheLockWhileTheShellBlockAndTheRootGo(): Unit =
        withTempDir { tmp ->
            val cfg = cfg(Path(tmp, ".ketch"))
            createDirAll(cfg.binDir)
            Path(cfg.binDir, "ketch").write("bin")
            cfg.markRoot()
            val fifo = Path(tmp, ".bashrc")
            assertEquals(0, mkfifo(fifo.toString(), 0b110_000_000.convert()), "mkfifo failed")
            val plan = UninstallPlan(root = cfg.root, shellFiles = listOf(fifo))
            val block = "# kept\n# >>> ketch >>>\nexport PATH=\"/tmp/ketch/bin:\$PATH\"\n# <<< ketch <<<\n# also kept\n"
            val fifoName = fifo.toString()

            runBlocking {
                val reached = Channel<Unit>(1)
                val release = Channel<Unit>(1)
                val writer =
                    async(Dispatchers.Default) {
                        // Plain posix calls: nothing here touches a kotlinx-io path while the other thread runs.
                        val fd = open(fifoName, O_WRONLY)
                        reached.send(Unit)
                        release.receive()
                        val bytes = block.encodeToByteArray()
                        bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
                        close(fd)
                    }
                val uninstaller = async(Dispatchers.Default) { runCatching { uninstallSelf(Ctx(cfg, Report.silent()), plan) } }

                if (withTimeoutOrNull(5.seconds) { reached.receive() } == null) {
                    // A reader unblocks `open` for write, so a failed test can finish.
                    close(open(fifoName, O_RDONLY))
                    release.send(Unit)
                    writer.await()
                    uninstaller.await()
                    fail("uninstall did not reach the shell file")
                }
                val lockFree = runCatching { Lock.acquirePath(cfg.lockFile, Report.silent()).close() }
                val lockFileThere = entryKind(cfg.lockFile) == EntryKind.File
                release.send(Unit)
                val removed = uninstaller.await().getOrThrow()
                writer.await()

                assertTrue(lockFree.exceptionOrNull() is KetchError.Busy, "the install lock was free while the shell block was being read")
                assertTrue(lockFileThere, "the lock file was already gone, so the root was deleted before the shell block")
                assertNull(entryKind(cfg.root), "the root survived uninstall: $removed")
                assertEquals("# kept\n# also kept\n", fifo.read())
                assertTrue(fifo in removed, "the shell file was not reported removed: $removed")
            }
        }
}
