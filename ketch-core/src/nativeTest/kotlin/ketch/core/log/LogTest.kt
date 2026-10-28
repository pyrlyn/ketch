package ketch.core.log

import fs.joinPath
import fs.readText
import ketch.core.config.Config
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogTest {
    @Test
    fun verboseWritesDebugDetailToTheLogFile(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "ketch.log")
            val previous = Log.swap(null)
            Log.swap(Log.open(path, Log.fileLevel(Level.Info, true), Format.Text))
            Log.record(Level.Debug, "root /tmp/ketch · target aarch64 · token no")
            Log.set(previous)

            val contents = readText(path)
            assertTrue("DEBUG" in contents, contents)
            assertTrue("root /tmp/ketch" in contents, contents)
        }

    @Test
    fun turningTheLogOffClosesTheSinkAnEarlierInitOpened(): Unit =
        withTempDir { dir ->
            val previous = Log.swap(null)
            val cfg = Config.load(joinPath(dir, "root"), Report.silent())
            Log.init(cfg.copy(logLevel = Level.Info), false)
            assertNotNull(Log.path(), "first operation logs")

            Log.init(cfg.copy(logLevel = Level.Off), false)
            val closed = Log.path()
            Log.swap(previous)

            assertNull(closed, "second operation, log off, must not reuse the sink")
        }
}
