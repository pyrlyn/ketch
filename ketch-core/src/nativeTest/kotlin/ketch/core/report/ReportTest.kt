package ketch.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ReportTest {
    private fun recording(): Pair<Recorder, Report> {
        val recorder = Recorder()
        return recorder to Report(recorder)
    }

    @Test
    fun anActivityEndsWhenItsWorkReturns() {
        val (recorder, report) = recording()
        assertEquals(7, report.activity("resolving rg").run { 7 })
        val events = recorder.events()
        val id = assertIs<Event.Began>(events[0]).id
        assertEquals(listOf(Event.Began(id, Task.Activity("resolving rg")), Event.Ended(id, null)), events)
    }

    @Test
    fun aDownloadReportsItsRunningTotalAndWhatArrived() {
        val (recorder, report) = recording()
        report.download("rg").use { sink ->
            sink.start(10, "rg.tar.gz")
            sink.advance(4)
            sink.advance(6)
            sink.finish("rg.tar.gz (10 B)")
        }
        val events = recorder.events()
        val id = assertIs<Event.Began>(events[0]).id
        assertEquals(
            listOf(
                Event.Began(id, Task.Download("rg", null)),
                Event.Sized(id, "rg.tar.gz", 10),
                Event.Progress(id, 4, 10),
                Event.Progress(id, 10, 10),
                Event.Ended(id, "rg.tar.gz (10 B)"),
            ),
            events,
        )
    }

    @Test
    fun aDownloadDroppedUnfinishedIsAbandonedNotEnded() {
        val (recorder, report) = recording()
        report.download("rg").close()
        val events = recorder.events()
        assertIs<Event.Abandoned>(events.last())
        assertFalse(events.any { it is Event.Ended })
    }

    @Test
    fun aBatchDownloadNamesItsBatch() {
        val (recorder, report) = recording()
        report.batch().use { batch -> batch.download("fd").use { it.finish("") } }
        val events = recorder.events()
        val batchId = assertIs<Event.Began>(events[0]).id
        val download = assertIs<Task.Download>(assertIs<Event.Began>(events[1]).task)
        assertEquals(batchId, download.batch)
        assertEquals(null, assertIs<Event.Ended>(events[2]).message)
        assertEquals(Event.Ended(batchId, null), events[3])
    }

    @Test
    fun aCounterCountsUpToItsTotal() {
        val (recorder, report) = recording()
        report.counter("checking", 2, "packages").use {
            it.inc()
            it.inc()
        }
        val done =
            recorder
                .events()
                .filterIsInstance<Event.Progress>()
                .filter { it.total == 2L }
                .map { it.done }
        assertEquals(listOf(1L, 2L), done)
    }

    @Test
    fun aLogReporterPassesEveryEventOn() {
        val (recorder, inner) = recording()
        val report = Report(LogReporter(inner))
        report.warn("careful")
        report.step("resolving", "rg")
        assertEquals(listOf(Event.Warn("careful"), Event.Status("resolving", "rg")), recorder.events())
    }
}
