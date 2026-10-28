// `ketch serve` as the desktop app runs it: the built binary, its whole input written ahead and closed. Questions
// and cancels while a call runs need an answer after the question, so ServerTest covers those in process.
package ketch.e2e

import ketch.core.config.Config
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.state.Lock
import ketch.e2e.support.Run
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.ketchWithInput
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.ok
import ketch.e2e.support.writeText
import ketch.wire.AppMessage
import ketch.wire.Call
import ketch.wire.Event
import ketch.wire.InstallOptions
import ketch.wire.Installed
import ketch.wire.Outcome
import ketch.wire.PROTOCOL
import ketch.wire.ServeMessage
import ketch.wire.Stage
import ketch.wire.Wire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ketch.wire.KetchError as WireError

class ServeTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun line(message: AppMessage) = Wire.json.encodeToString(AppMessage.serializer(), message)

    /** Run `ketch serve` on [lines] and read back every message it wrote; it must end on its own once input does. */
    private fun serve(vararg lines: String): List<ServeMessage> {
        val run = sandbox.ketchWithInput(listOf("serve"), lines.joinToString("") { "$it\n" })
        assertEquals(0, run.code, run.toString())
        return messages(run)
    }

    private fun messages(run: Run) =
        run.stdout
            .lines()
            .filter { it.isNotEmpty() }
            .map { Wire.json.decodeFromString(ServeMessage.serializer(), it) }

    private fun results(messages: List<ServeMessage>) = messages.filterIsInstance<ServeMessage.Result>().associateBy { it.id }

    @Test
    fun itGreetsAnswersEachCallAndEndsWithItsInput() {
        val messages = serve(line(AppMessage.Start(1, Call.InstalledPackages)), line(AppMessage.Start(2, Call.Root)))
        val hello = assertIs<ServeMessage.Hello>(messages.first())
        assertEquals(PROTOCOL, hello.protocol)
        assertEquals("ketch ${hello.version}", sandbox.ok("--version").trim())
        val results = results(messages)
        assertEquals(Outcome.Ok(JsonArray(emptyList())), results.getValue(1).outcome)
        assertEquals(Outcome.Ok(JsonPrimitive(sandbox.root.toString())), results.getValue(2).outcome)
    }

    @Test
    fun aLocalInstallReportsItsStepsBeforeItsResult() {
        val program = sandbox.fixture("hello")
        writeText(program, "#!/bin/sh\necho hi\n")
        makeExecutable(program)
        val install = Call.Install(listOf("local:$program"), InstallOptions())
        val messages = serve(line(AppMessage.Start(7, install)))
        val events = messages.filterIsInstance<ServeMessage.Reported>()
        assertTrue(events.all { it.id == 7L }, events.toString())
        assertTrue(events.any { (it.event as? Event.Step)?.stage == Stage.Installing }, events.toString())
        assertIs<ServeMessage.Result>(messages.last())
        val placed = assertIs<Outcome.Ok>(results(messages).getValue(7).outcome).value
        assertEquals(
            "hello",
            Wire.json
                .decodeFromJsonElement(ListSerializer(Installed.serializer()), placed)
                .single()
                .pkg.name,
        )
        assertTrue("hello" in sandbox.ok("list"))
    }

    @Test
    fun aCallThatNeedsTheLockHeldElsewhereIsBusy() {
        sandbox.ok("list")
        Lock.acquire(Ctx(Config.load(sandbox.root, Report.silent()), Report.silent())).use {
            val messages = serve(line(AppMessage.Start(1, Call.Uninstall(listOf("nope")))))
            assertIs<WireError.Busy>(assertIs<Outcome.Error>(results(messages).getValue(1).outcome).error)
        }
    }

    @Test
    fun aLineThatIsNoMessageIsRejectedAndServingGoesOn() {
        val messages = serve("not json", line(AppMessage.Start(1, Call.Root)))
        assertIs<ServeMessage.Rejected>(messages[1])
        assertIs<Outcome.Ok>(results(messages).getValue(1).outcome)
    }

    @Test
    fun anUnknownArgumentIsAUsageErrorAndTheHelpDoesNotNameServe() {
        val run = sandbox.ketch("serve", "--bogus")
        assertEquals(2, run.code, run.toString())
        assertTrue("--bogus" in run.stderr, run.stderr)
        assertTrue(" serve " !in sandbox.ok("--help"))
    }
}
