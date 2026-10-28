// CLI behaviour for `auto_update`, and for stopping a process that holds a file `ketch upgrade` is about to replace
// (Rust tests/auto_update.rs).
@file:OptIn(ObsoleteWorkersApi::class)

package ketch.e2e

import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.SLEEPER_READY
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.ketchOverrides
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.tarGz
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.usleep
import process.Command
import process.runBounded
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Off-host so nothing answers: auto-update is switched on only to see it announced. */
private val NOWHERE = mapOf("KETCH_AUTO_UPDATE" to "true", "KETCH_GITHUB_API" to "http://127.0.0.1:1")

/** Run the installed tool with [job]'s ready flag until it ends or is stopped; its outcome does not matter. */
private fun occupy(job: Pair<String, String>) = runCatching { Command(job.first).env(SLEEPER_READY, job.second).runBounded(60.seconds, 1024) }.isSuccess

class AutoUpdateTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun publishTool(version: String) {
        val native = sandbox.asset(nativeName("testtool", version), tarGz(Entry.program("testtool-$version/bin/testtool", "testtool $version")))
        sandbox.publish("testtool", listOf(Release(version, listOf(native))))
    }

    private fun publishSleeper(version: String) {
        val native = sandbox.asset(nativeName("testtool", version), tarGz(Entry.sleeper("testtool-$version/bin/testtool")))
        sandbox.publish("testtool", listOf(Release(version, listOf(native))))
    }

    /** Wait until [path] exists. The bound only turns a sleeper that never starts into a failure instead of a hang. */
    private fun waitFor(path: Path) {
        val deadline = TimeSource.Monotonic.markNow() + 60.seconds
        while (!SystemFileSystem.exists(path)) {
            assertFalse(deadline.hasPassedNow(), "$path never appeared")
            usleep(10_000u)
        }
    }

    @Test
    fun installDoesNotMentionAutoUpdateWhenItIsOff() {
        publishTool("1.0.0")
        val out = sandbox.ketch("install", "test:testtool@1.0.0", "--yes")
        assertTrue(out.success, out.stderr)
        assertFalse("auto-update" in out.stderr, "disabled auto-update must stay silent, got ${out.stderr}")
    }

    @Test
    fun installPrintsThatAutoUpdateIsEnabled() {
        publishTool("1.0.0")
        val out = sandbox.ketchOverrides(listOf("install", "test:testtool@1.0.0", "--yes"), NOWHERE)
        assertTrue(out.success, out.stderr)
        assertTrue("auto-update" in out.stderr, "enabled auto-update must name itself, got ${out.stderr}")
        assertTrue("enabled" in out.stderr, "enabled auto-update must say so, got ${out.stderr}")
    }

    @Test
    fun upgradePrintsThatAutoUpdateIsEnabled() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        publishTool("2.0.0")
        val out = sandbox.ketchOverrides(listOf("upgrade", "--yes"), NOWHERE)
        assertTrue(out.success, out.stderr)
        assertTrue("auto-update" in out.stderr, "upgrade with auto-update on must name it, got ${out.stderr}")
    }

    @Test
    fun upgradeStopsAProcessHoldingTheBinaryWhenYes() {
        publishSleeper("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")

        val link = sandbox.bin.resolve("testtool").toString()
        val ready = sandbox.root.resolve("sleeper-ready").toString()
        // The occupant runs on a worker, since a run here waits for its child; upgrade is what ends it.
        val worker = Worker.start(name = "occupant")
        val occupant = worker.execute(TransferMode.SAFE, { link to ready }, ::occupy)
        try {
            // A fixed pause lost the race under load in Rust: the shell had not opened the script yet.
            waitFor(Path(ready))

            publishSleeper("2.0.0")
            val out = sandbox.ketch("upgrade", "--yes")
            assertTrue(out.success, out.stderr)
            assertTrue("in use" in out.stderr || "stopping" in out.stderr, "upgrade --yes must stop the occupant, got ${out.stderr}")
        } finally {
            occupant.result
            worker.requestTermination().result
        }
    }
}
