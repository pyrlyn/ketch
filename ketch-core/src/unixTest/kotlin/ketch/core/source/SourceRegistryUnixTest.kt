package ketch.core.source

import fs.createDirAll
import fs.setMode
import ketch.core.config.Config
import ketch.core.report.Ctx
import ketch.core.report.Event
import ketch.core.report.Recorder
import ketch.core.report.Report
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRegistryUnixTest {
    private fun plugin(
        dir: Path,
        name: String,
        body: String,
    ) {
        val path = Path(dir, "$PLUGIN_PREFIX$name")
        path.write("#!/bin/sh\n$body\n")
        setMode(path, "755".toInt(8))
    }

    /** A broken plugin is warned about and skipped; the one beside it still loads, and the plugins dir wins over PATH. */
    @Test
    fun aBrokenPluginIsSkippedAndTheRestLoad(): Unit =
        withTempDir { tmp ->
            val recorder = Recorder()
            val report = Report(recorder)
            val cfg = Config.load(Path(tmp, "root"), report)
            createDirAll(cfg.pluginDir)
            val onPath = Path(tmp, "path")
            createDirAll(onPath)
            plugin(cfg.pluginDir, "demo", "echo '{\"protocol\":$PROTOCOL_VERSION,\"scheme\":\"demo\"}'")
            plugin(cfg.pluginDir, "broken", "exit 3")
            plugin(onPath, "demo", "echo '{\"protocol\":$PROTOCOL_VERSION,\"scheme\":\"shadowed\"}'")
            withEnv(emptyList(), mapOf("PATH" to onPath.toString())) {
                val registry = SourceRegistry.load(Ctx(cfg, report))
                assertEquals(listOf("github", "local", "demo"), registry.schemes())
            }
            val warnings = recorder.events().filterIsInstance<Event.Warn>().map { it.detail }
            assertEquals(1, warnings.size, warnings.toString())
            assertTrue(warnings[0].startsWith("ignoring plugin: "), warnings[0])
        }
}
