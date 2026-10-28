package ketch.core.install

import fs.joinPath
import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.decide.Decider
import ketch.core.error.KetchError
import ketch.core.extract.Extractor
import ketch.core.model.Arch
import ketch.core.model.LinkRecord
import ketch.core.model.Os
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.TargetSpec
import ketch.core.platform.AssetScore
import ketch.core.platform.DoctorCheck
import ketch.core.platform.Placement
import ketch.core.process.Occupant
import ketch.core.report.Ctx
import ketch.core.report.Event
import ketch.core.report.Recorder
import ketch.core.report.Report
import ketch.core.report.Stage
import ketch.core.report.Task
import ketch.core.source.ListOpts
import ketch.core.source.Source
import ketch.core.source.SourceRegistry
import ketch.core.state.State
import ketch.core.testing.program
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallUnixTest {
    /** The event sequence a front end draws an install from: every stage in order, the download as one task that ends, and nothing printed. */
    @Test
    fun aLocalInstallReportsEachStageThroughTheReporter(): Unit =
        withTempDir { tmp ->
            val recorder = Recorder()
            val report = Report(recorder)
            val cfg = Config.load(joinPath(tmp, "root"), report)
            val cx = Ctx(cfg, report)
            val tool = program(tmp, "tool", "#!/bin/sh\necho tool\n")
            install(cx, SourceRegistry.builtinOnly(cx), State(), InstallRequest(PackageSpec.parse("local:$tool")))

            val events = recorder.events()
            assertEquals(
                listOf(Stage.Resolving, Stage.Downloading, Stage.Verifying, Stage.Extracting, Stage.Trusting, Stage.Installing),
                events.filterIsInstance<Event.Step>().map { it.stage },
            )
            val download =
                assertNotNull(
                    events.filterIsInstance<Event.Began>().firstOrNull { (it.task as? Task.Download)?.batch == null && it.task is Task.Download },
                    "the download is announced",
                ).id
            assertTrue(events.any { it is Event.Ended && it.id == download }, "a download that arrived ends rather than being abandoned: $events")
            assertTrue(events.any { it is Event.Status && it.verb == "resolving" && it.detail.startsWith("tool ") }, "$events")
            assertTrue(events.none { it is Event.Warn }, "a clean install warns about nothing: $events")
        }
}
