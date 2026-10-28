package ketch.core.platform

import fs.copyFile
import fs.createDirAll
import fs.entryKind
import fs.listDir
import fs.readText
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.process.using
import ketch.core.report.Report
import ketch.core.shell.nativeSpelling
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.usleep
import process.Command
import process.Output
import process.runBounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PlatformWindowsTest {
    /**
     * A live Windows image can be renamed but not deleted or overwritten. `FILE_SHARE_NONE` is the wrong simulation (it
     * blocks rename too), so a copied `cmd.exe` really runs from [path] until closed.
     */
    private class RunningCopy(
        private val path: Path,
    ) : AutoCloseable {
        private val run: Deferred<Result<Output>>
        private val pid: Long

        init {
            copyFile(Path("C:\\Windows\\System32\\cmd.exe"), path)
            val command = Command(nativeSpelling(path), listOf("/c", "ping -n 60 127.0.0.1 >nul"))
            run = CoroutineScope(Dispatchers.Default).async { runCatching { command.runBounded(2.minutes, 1L shl 20) } }
            val deadline = TimeSource.Monotonic.markNow() + 30.seconds
            var found = using(listOf(path), Report.silent()).firstOrNull()
            while (found == null) {
                if (run.isCompleted) fail("the copy exited before it ran: ${runBlocking { run.await() }.map { "${it.status} ${it.stdout.decodeToString()} ${it.stderr.decodeToString()}" }}")
                if (deadline.hasPassedNow()) fail("$path never showed as running")
                usleep(50.milliseconds.inWholeMicroseconds.toUInt())
                found = using(listOf(path), Report.silent()).firstOrNull()
            }
            pid = found.pid
        }

        /** With its tree: the ping it started holds the output pipe the run waits on. */
        override fun close() {
            Command("taskkill", listOf("/PID", pid.toString(), "/T", "/F")).runBounded(30.seconds, 1L shl 20)
            runBlocking { run.await() }
        }
    }

    @Test
    fun clearForReplaceRenamesAsideARunningExe(): Unit =
        withTempDir { tmp ->
            val path = Path(tmp, "ketch.exe")
            RunningCopy(path).use {
                clearForReplace(path)
                assertNull(entryKind(path), "destination name must be free for the replacement copy")
                val asides = listDir(tmp).filter { it.startsWith("ketch.exe.old.") }
                assertEquals(1, asides.size, "expected one aside, got $asides")
                path.write("new")
                assertEquals("new", readText(path))
            }
        }

    @Test
    fun copyBinaryReplacesARunningRecordedExe(): Unit =
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            createDirAll(payload)
            Path(payload, "tool.exe").write("v2")
            val store = Path(tmp, "store/tool/1.0")
            createDirAll(store)
            val storeTarget = Path(store, "tool.exe")
            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val link = Path(bin, "tool.exe")
            RunningCopy(link).use {
                copyFile(link, storeTarget)
                val recorded = listOf(LinkRecord(link, storeTarget, LinkKind.CopiedFile, LinkRole.Binary))
                copyBinary(Path(payload, "tool.exe"), bin, "tool.exe", recorded, Claims())
                assertEquals("v2", readText(link))
            }
        }

    /** A Windows name may be any UTF-16, paired or not; the store swap must keep such a name on disk as it was. */
    @Test
    fun aStoreSwapKeepsANameThatIsNotValidUnicode(): Unit =
        withTempDir { tmp ->
            val name = "tool\uD800"
            val store = Path(tmp, name)
            createDirAll(store)
            Path(store, "tool").write("working")
            val payload = Path(tmp, "payload")
            createDirAll(payload)
            Path(payload, "tool").write("new")
            swapIntoStore(payload, store) { _, _ -> fail("rename should stay on one filesystem") }
            assertEquals(listOf(name, "$name.old"), listDir(tmp).sorted())
            assertEquals("working", readText(Path(sibling(store, ".old"), "tool")))
        }
}
