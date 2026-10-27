package ketch.core.hooks

import fs.readText
import ketch.core.error.KetchError
import ketch.core.model.Hooks
import ketch.core.model.ManifestOrigin
import ketch.core.report.Recorder
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import ketch.core.report.Event as Said

class HooksTest {
    private fun hooks(afterInstall: String) = Hooks(afterInstall = afterInstall)

    private fun context(
        prefix: Path,
        previous: String? = null,
        report: Report = Report.silent(),
    ) = Context("tool", "2.0.0", previous, prefix, Path("bin"), Path("root"), report)

    @Test
    fun aMissingHookIsANoOp() {
        val ctx = context(Path("nowhere"))
        run(Hooks(), Event.AfterInstall, ctx)
        // Only the event asked for runs: an `after_install` hook is silent on uninstall.
        run(hooks("exit 1"), Event.AfterUninstall, ctx)
    }

    @Test
    fun aHookRunsInThePrefixWithThePackageInItsEnvironment(): Unit =
        withTempDir { tmp ->
            val prefix = Path(tmp, "prefix")
            SystemFileSystem.createDirectories(prefix)
            val out = Path(tmp, "out")
            val script =
                "printf '%s %s %s %s\\n%s\\n' \"\$KETCH_HOOK\" \"\$KETCH_PACKAGE\" \"\$KETCH_VERSION\" " +
                    "\"\$KETCH_PREVIOUS_VERSION\" \"\$PWD\" > '$out'"
            run(Hooks(afterUpdate = script), Event.AfterUpdate, context(prefix, "1.0.0"))
            val lines = readText(out).lines()
            assertEquals("after_update tool 2.0.0 1.0.0", lines[0].trim())
            assertEquals(SystemFileSystem.resolve(prefix), SystemFileSystem.resolve(Path(lines[1].trim())))
        }

    @Test
    fun aFailingHookReportsItsStderr() {
        val e = assertFailsWith<KetchError> { run(hooks("echo broken >&2; exit 3"), Event.AfterInstall, context(Path("nowhere"))) }
        assertTrue("after_install hook for tool" in e.message.orEmpty(), e.message)
        assertEquals(listOf("broken"), e.details())
    }

    @Test
    fun aHookThatHangsIsStoppedAtTheDeadline() {
        val started = TimeSource.Monotonic.markNow()
        val e =
            assertFailsWith<KetchError> {
                runWith(hooks("sleep 30"), Event.AfterInstall, context(Path("nowhere")), 300.milliseconds)
            }
        assertTrue(started.elapsedNow() < 10.seconds, "waited the hook out instead of stopping it")
        assertTrue("was stopped" in e.message.orEmpty(), e.message)
    }

    @Test
    fun onlyTheUsersOwnManifestsMayRunHooks() {
        assertTrue(allowed(ManifestOrigin.User(Path("m/tool.toml"))))
        assertFalse(allowed(ManifestOrigin.Registry(Path("r/tool/ketch.toml"))))
        assertFalse(allowed(ManifestOrigin.Builtin))
        assertFalse(allowed(ManifestOrigin.Inferred))
    }

    @Test
    fun aHookIsAnnouncedAndItsOutputIsTraced() {
        val recorder = Recorder()
        run(hooks("echo hello"), Event.AfterInstall, context(Path("nowhere"), report = Report(recorder)))
        assertEquals(
            listOf(Said.Status("running", "after_install hook for tool"), Said.Debug("after_install: hello")),
            recorder.events(),
        )
    }

    @Test
    fun aFailedAfterHookIsAWarningNotAnError() {
        val recorder = Recorder()
        runOrWarn(hooks("exit 3"), Event.AfterInstall, context(Path("nowhere"), report = Report(recorder)))
        assertTrue(recorder.events().any { it is Said.Warn && "after_install hook for tool" in it.detail })
    }
}
