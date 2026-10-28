package ketch.core.process

import fs.joinPath
import ketch.core.report.Report
import ketch.core.shell.nativeSpelling
import ketch.core.state.processAlive
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.usleep
import process.Command
import process.ProcessException
import process.runBounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ProcessWindowsTest {
    /**
     * A script runs inside cmd.exe, so only its command line names it. The kit hides the child's pid, so stopping the
     * listed pid and seeing the run end is what proves the listing found that child.
     */
    @Test
    fun listsAChildRunningACmdScript(): Unit =
        withTempDir { tmp ->
            val script = joinPath(tmp, "tool.cmd")
            script.write("@echo off\r\nping -n 30 127.0.0.1 >nul\r\n")
            val run = CoroutineScope(Dispatchers.Default).async { runCatching { Command(nativeSpelling(script)).runBounded(2.minutes, 1L shl 20) } }
            val deadline = TimeSource.Monotonic.markNow() + 30.seconds
            var found: Occupant? = null
            while (found == null) {
                if (run.isCompleted) fail("the script exited before it was listed: ${runBlocking { run.await() }.map { "${it.status} ${it.stdout.decodeToString()} ${it.stderr.decodeToString()}" }}")
                if (deadline.hasPassedNow()) fail("no occupant for $script")
                found = using(listOf(script), Report.silent()).firstOrNull()
                if (found == null) usleep(50.milliseconds.inWholeMicroseconds.toUInt())
            }
            assertEquals(script, found.path)
            forceExit(found.pid)
            val status = runBlocking { run.await() }.getOrThrow().status
            assertFalse(status.success, "the script ran to completion instead of being stopped: $status")
        }

    @Test
    fun cmdHitsFoldsCommandLineCase(): Unit =
        withTempDir { tmp ->
            val path = joinPath(tmp, "tool.cmd")
            path.write("@echo off\r\n")
            val shouted = nativeSpelling(path).uppercase()
            assertTrue(cmdHits("C:\\WINDOWS\\system32\\cmd.exe /c \"$shouted\"", path, pathKey(path)))
        }

    @Test
    fun aCommandThatOutlivesTheTimeoutIsStopped() {
        val started = TimeSource.Monotonic.markNow()
        val e =
            runCatching { Command("cmd.exe", listOf("/c", "ping -n 30 127.0.0.1 >nul")).runBounded(300.milliseconds, 1L shl 20) }
                .exceptionOrNull()
        val pid = assertNotNull((e as? ProcessException)?.stoppedPid, "expected the child to be stopped, got $e").toLong()
        assertTrue(started.elapsedNow() < 10.seconds, "the wait must stay bounded")
        val detail = stoppedDetail("cmd.exe", pid, 300.milliseconds)
        assertTrue("was stopped" in detail, detail)
        val deadline = TimeSource.Monotonic.markNow() + 2.seconds
        while (processAlive(pid) && !deadline.hasPassedNow()) usleep(50.milliseconds.inWholeMicroseconds.toUInt())
        assertFalse(processAlive(pid), "pid $pid was not stopped")
    }
}
