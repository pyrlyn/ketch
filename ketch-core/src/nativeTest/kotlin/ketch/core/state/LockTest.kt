@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package ketch.core.state

import fs.FsException
import fs.atomic
import fs.createNew
import fs.joinPath
import fs.listDir
import fs.readText
import fs.removeFile
import fs.setModifiedAgo
import ketch.core.config.HOST_WINDOWS
import ketch.core.error.KetchError
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.usleep
import process.Command
import process.runBounded
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

class LockTest {
    private val silent = Report.silent()

    private fun acquire(path: Path) = Lock.acquirePath(path, silent)

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    private fun write(
        path: Path,
        text: String,
    ) = atomic(path, text.encodeToByteArray())

    private val me get() = getpid().toLong()

    @Test
    fun aLockLeftByADeadProcessIsReclaimedWithoutResidue(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            // Above the pid ceiling, so it can never name a running process.
            write(path, "999999")
            val lock = acquire(path)
            assertEquals(me.toString(), readText(path))
            lock.close()
            assertEquals(emptyList(), listDir(dir), "reclaim left files behind")
        }

    @Test
    fun aSecondLockInThisProcessIsBusyUntilTheFirstCloses(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, ".lock")
            val held = acquire(path)
            assertTrue(exists(path))
            assertEquals(me, assertFailsWith<KetchError.Busy> { acquire(path) }.pid)
            // The refused attempt must not have released the holder's lock file.
            assertTrue(exists(path))
            held.close()
            assertFalse(exists(path))
            acquire(path).close()
        }

    @Test
    fun aLockHeldUnderOnePathDoesNotBlockAnotherPath(): Unit =
        withTempDir { dir ->
            acquire(joinPath(dir, "a")).use { acquire(joinPath(dir, "b")).close() }
        }

    @Test
    fun aFailedAcquireLeavesNoClaimBehind(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            // A live foreign holder: busy, and must not poison later attempts. pid 1 is init only on Unix.
            if (!HOST_WINDOWS) {
                write(path, "1")
                assertFailsWith<KetchError.Busy> { acquire(path) }
                removeFile(path)
            }
            acquire(path).close()
        }

    @Test
    fun aLockFileNamingThisProcessWithoutAHolderIsStale(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            write(path, me.toString())
            acquire(path).close()
            assertFalse(exists(path))
        }

    @Test
    fun aHeldLockCanBeContendedFromAnotherThread(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            acquire(path).use {
                val other = runBlocking { withContext(Dispatchers.Default) { runCatching { acquire(path).close() } } }
                assertIs<KetchError.Busy>(other.exceptionOrNull())
            }
        }

    @Test
    fun anEmptyLockFileIsBusyUntilItIsOld(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            // What a holder that has created the file but not yet written its pid looks like from outside.
            createNew(path, ByteArray(0))
            assertEquals(null, assertFailsWith<KetchError.Busy> { acquire(path) }.pid)
            assertEquals("", readText(path))
            setModifiedAgo(path, UNREADABLE_GRACE * 2)
            acquire(path).close()
            assertFalse(exists(path))
        }

    @Test
    fun aLateReclaimNeverTakesAFreshLockAway(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            write(path, "999999")
            // This process reclaims the dead pid's lock first...
            val held = acquire(path)
            // ...while another, which judged the same file stale a moment earlier, reclaims now.
            when (val outcome = reclaim(path, OTHER_PID)) {
                is Reclaim.Busy -> assertEquals(me, outcome.pid)
                else -> fail("a live lock was reclaimed: $outcome")
            }
            assertEquals(me.toString(), readText(path))
            held.close()
            assertFalse(exists(path))
        }

    @Test
    fun closingALockLeavesAFileAnotherProcessWrote(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "lock")
            val held = acquire(path)
            removeFile(path)
            write(path, "1")
            held.close()
            assertEquals("1", readText(path))
        }

    /** Run by [processesNeverHoldTheLockTogether] in child processes; a plain test run finds no variable and does nothing. */
    @Test
    fun lockContender() {
        val dir = getenv(CONTENDER)?.toKString()?.let(::Path) ?: return
        val lock = joinPath(dir, "lock")
        val inside = joinPath(dir, "inside")
        // Proof for the parent that the filter reached this test, not an empty run that passes.
        write(joinPath(dir, "ran.$me"), "")
        for (round in 0 until ROUNDS) {
            val held = runCatching { acquire(lock) }.getOrNull() ?: continue
            try {
                createNew(inside, ByteArray(0))
            } catch (e: FsException) {
                write(joinPath(dir, "overlap.$me"), e.kind.name)
            }
            usleep(HOLD_MICROS)
            runCatching { removeFile(inside) }
            if (round % CRASH_EVERY == 0) {
                // A crash: the file stays behind naming a pid that is gone, and every other contender judges it
                // stale at once. Written in place, as Rust's `fs::write` does: replacing it would need every other
                // handle on it to allow deletion, and on Windows one often does not for about a second.
                SystemFileSystem.sink(lock).buffered().use { it.writeString("999999") }
                releaseClaim(lock)
            } else {
                held.close()
            }
        }
    }

    @Test
    fun processesNeverHoldTheLockTogether(): Unit =
        withTempDir { dir ->
            write(joinPath(dir, "lock"), "999999")
            // The test binary itself, filtered down to the contender.
            val exe = checkNotNull(Platform.programName) { "no program name to run the contenders with" }
            val filter = "--ktest_filter=ketch.core.state.LockTest.${LockTest::lockContender.name}"
            val children = List(CONTENDERS) { Command(exe, listOf(filter)).env(CONTENDER, dir.toString()) }
            // runBounded waits for each child; running them one after another would never contend, so they start
            // together from a pool of threads.
            val results =
                runBlocking {
                    children
                        .map { child ->
                            async(Dispatchers.Default) { child.runBounded(2.minutes, 1L shl 20) }
                        }.map { it.await() }
                }
            for (out in results) assertTrue(out.status.success, out.stdout.decodeToString() + out.stderr.decodeToString())
            assertEquals(CONTENDERS, listDir(dir).count { it.startsWith("ran.") }, "not every contender ran")
            val overlaps = listDir(dir).filter { it.startsWith("overlap.") }
            assertEquals(emptyList(), overlaps, "two processes held the lock")
        }

    private companion object {
        const val CONTENDER = "KETCH_TEST_LOCK_CONTENDER"
        const val CONTENDERS = 6
        const val ROUNDS = 150
        const val CRASH_EVERY = 7
        const val HOLD_MICROS = 300u
        const val OTHER_PID = 999_998L
    }
}
