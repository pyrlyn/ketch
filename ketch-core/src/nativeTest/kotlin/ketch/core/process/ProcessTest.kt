@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package ketch.core.process

import fs.atomic
import fs.copyFile
import fs.setMode
import fs.textOrEmpty
import ketch.core.config.Config
import ketch.core.report.Ctx
import ketch.core.report.Event
import ketch.core.report.Recorder
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.SIGKILL
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.kill
import platform.posix.sleep
import platform.posix.usleep
import process.Command
import process.ExitStatus
import process.Output
import process.runBounded
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ProcessTest {
    /**
     * A copy of this test binary, filtered down to [sleepsOnlyWhenSpawnedAsTheSleeper]. macOS launch constraints
     * SIGKILL a copied platform binary such as `/bin/sleep` within milliseconds of exec, so a copy of it only looked
     * listed when `lsof` won that race; this binary is not a platform binary.
     */
    private class Sleeper(
        dir: Path,
    ) : AutoCloseable {
        val copy = Path(dir, "sleeper")
        private val pidFile = Path(dir, "pid")
        val run: Deferred<Result<Output>>

        init {
            copyFile(Path(checkNotNull(Platform.programName) { "no program name to copy" }), copy)
            setMode(copy, "755".toInt(8))
            val filter = "--ktest_filter=ketch.core.process.ProcessTest.${ProcessTest::sleepsOnlyWhenSpawnedAsTheSleeper.name}"
            val command = Command(copy.toString(), listOf(filter)).env(SLEEPER_ENV, dir.toString())
            // runBounded waits for the child, so it waits on another thread while the test lists it.
            run = CoroutineScope(Dispatchers.Default).async { runCatching { command.runBounded(2.minutes, 1L shl 20) } }
        }

        /** The pid the sleeper wrote once it was running, or null before that. */
        fun pid(): Long? = textOrEmpty(pidFile).trim().toLongOrNull()

        fun await(): Output = runBlocking { run.await() }.getOrThrow()

        override fun close() {
            pid()?.let { kill(it.toInt(), SIGKILL) }
            runBlocking { run.await() }
        }
    }

    /**
     * The occupant listed for [path]. The retry is a wall-clock backstop, and a child that already exited fails at
     * once with its status instead of polling a scan that can never succeed.
     */
    private fun waitFor(
        path: Path,
        sleeper: Sleeper,
    ): Pair<Occupant, Long> {
        val deadline = TimeSource.Monotonic.markNow() + 30.seconds
        while (true) {
            if (sleeper.run.isCompleted) fail("sleeper exited before it was listed: ${runBlocking { sleeper.run.await() }}")
            val pid = sleeper.pid()
            val found = pid?.let { using(listOf(path), Report.silent()).firstOrNull() }
            if (pid != null && found != null) return found to pid
            if (deadline.hasPassedNow()) fail("no occupant for $path")
            usleep(20.milliseconds.inWholeMicroseconds.toUInt())
        }
    }

    /** Not a claim about ketch: the body a sleeper runs. Run normally, without the variable, it returns at once. */
    @Test
    fun sleepsOnlyWhenSpawnedAsTheSleeper() {
        val dir = getenv(SLEEPER_ENV)?.toKString()?.let(::Path) ?: return
        atomic(Path(dir, "pid"), getpid().toString().encodeToByteArray())
        sleep(SLEEP_SECONDS)
    }

    @Test
    fun listsAChildRunningFromACopiedFile() {
        withTempDir { tmp ->
            Sleeper(tmp).use { sleeper ->
                val (found, pid) = waitFor(sleeper.copy, sleeper)
                assertEquals(pid, found.pid)
                assertFalse(sleeper.run.isCompleted, "sleeper exited while being listed")
            }
        }
    }

    @Test
    fun yesStopsTheChildHoldingTheFile() {
        withTempDir { tmp ->
            Sleeper(tmp).use { sleeper ->
                val (found, pid) = waitFor(sleeper.copy, sleeper)
                assertEquals(pid, found.pid)
                val recorder = Recorder()
                val report = Report(recorder)
                val cfg = Config.load(tmp, report)
                offerToStop(listOf(sleeper.copy), true, Ctx(cfg, report))
                assertTrue(Event.Status("stopping", "pid ${found.pid}") in recorder.events(), "`--yes` must say which process it stops")
                // Awaited, not polled: a child `offerToStop` missed runs out its 30 s sleep and exits successfully,
                // which the assertion rejects.
                val status = sleeper.await().status
                assertIs<ExitStatus.Signaled>(status, "pid ${found.pid} was not stopped by a signal: $status")
            }
        }
    }

    /** A script, with its path spelled the way Windows command lines are. */
    private fun script(
        dir: Path,
        name: String,
    ): Pair<Path, String> {
        val path = Path(dir, name)
        path.write("#!/bin/sh\n")
        return path to path.toString().replace('/', '\\')
    }

    @Test
    fun aNeighbourWhoseNameExtendsThePathIsNotAnOccupant() {
        withTempDir { tmp ->
            val (rg, spelled) = script(tmp, "rg")
            val key = pathKey(rg)
            for (cmdline in listOf("\"${spelled}a\" --files", "$spelled-helper", "$spelled.exe", "$spelled\\sub")) {
                assertFalse(cmdHits(cmdline, rg, key), cmdline)
            }
            val raw = rg.toString()
            for (args in listOf("${raw}a\u0000--files\u0000", "sh\u0000$raw-helper\u0000")) {
                assertFalse(argsHit(args, rg, key), args)
            }
        }
    }

    @Test
    fun aScriptNamedAsAWholeArgumentIsAnOccupant() {
        withTempDir { tmp ->
            val (tool, spelled) = script(tmp, "tool.cmd")
            val key = pathKey(tool)
            // How Rust's `Command` starts a batch file: the path in two pairs of quotes.
            val cmdlines =
                listOf(
                    "C:\\WINDOWS\\system32\\cmd.exe /e:ON /v:OFF /d /c \"\"$spelled\" arg\"",
                    "cmd /c $spelled arg",
                    "cmd /c \"$spelled\"",
                )
            for (cmdline in cmdlines) assertTrue(cmdHits(cmdline, tool, key), cmdline)
            assertTrue(argsHit("/bin/sh\u0000$tool\u0000--flag\u0000", tool, key))
        }
    }

    @Test
    fun aProgramNamedOnAnotherCommandLineIsNotAnOccupant() {
        withTempDir { tmp ->
            val rg = Path(tmp, "rg")
            val spelled = rg.toString().replace('/', '\\')
            val key = pathKey(rg)
            val magics =
                listOf(
                    byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
                    byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0x90.toByte(), 0),
                    byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte()),
                )
            for (magic in magics) {
                atomic(rg, magic)
                // An editor or a shell holding the path, not running it.
                assertFalse(cmdHits("notepad.exe $spelled", rg, key))
                assertFalse(argsHit("vim\u0000$rg\u0000", rg, key))
            }
        }
    }

    // Rust runs these two on Windows, where listing spawns a program; on macOS `lsof` goes through the same path.
    @Test
    fun aListingThatFinishesIsReturned() {
        val out = outputBounded(Command("/bin/sh", listOf("-c", "echo found")), 10.seconds, Report.silent())
        assertTrue(
            out
                ?.stdout
                ?.decodeToString()
                .orEmpty()
                .contains("found"),
        )
    }

    @Test
    fun aListingThatOutlivesTheTimeoutIsStoppedAndWarnedAbout() {
        val recorder = Recorder()
        val started = TimeSource.Monotonic.markNow()
        assertNull(outputBounded(Command("/bin/sh", listOf("-c", "sleep 30")), 300.milliseconds, Report(recorder)))
        assertTrue(started.elapsedNow() < 10.seconds, "the wait must stay bounded")
        val warning =
            recorder
                .events()
                .filterIsInstance<Event.Warn>()
                .single()
                .detail
        assertTrue(warning.startsWith("/bin/sh (pid ") && warning.contains("was stopped"), warning)
    }

    private companion object {
        const val SLEEPER_ENV = "KETCH_TEST_SLEEPER"
        const val SLEEP_SECONDS = 30u
    }
}
