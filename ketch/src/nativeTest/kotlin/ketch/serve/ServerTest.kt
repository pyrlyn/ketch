package ketch.serve

import fs.TempDir
import fs.createDirAll
import fs.isDir
import fs.joinPath
import ketch.core.config.Config
import ketch.core.report.Report
import ketch.wire.AppMessage
import ketch.wire.Call
import ketch.wire.Outcome
import ketch.wire.PROTOCOL
import ketch.wire.Question
import ketch.wire.ServeMessage
import ketch.wire.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import ketch.wire.Answer as WireAnswer
import ketch.wire.KetchError as WireError

class ServerTest {
    /** A server over a scratch root, fed and read line by line as the app would. */
    private class Session(
        val dir: Path,
    ) {
        private val input = Channel<String?>(Channel.UNLIMITED)
        private val output = Channel<ServeMessage>(Channel.UNLIMITED)
        val root = joinPath(dir, "root")
        private val server =
            Server(KetchCore(root), { runBlocking { input.receive() } }) {
                output.trySend(Wire.json.decodeFromString(ServeMessage.serializer(), it))
            }
        val job = CoroutineScope(Dispatchers.Default).launch { server.serve() }

        fun send(message: AppMessage) = line(Wire.json.encodeToString(AppMessage.serializer(), message))

        fun line(text: String?) {
            input.trySend(text)
        }

        fun next(): ServeMessage = runBlocking { withTimeout(30.seconds) { output.receive() } }

        /** The next message that is not an event: events come and go with the core's wording. */
        fun nextNotEvent(): ServeMessage {
            while (true) {
                val message = next()
                if (message !is ServeMessage.Reported) return message
            }
        }

        fun ask(): ServeMessage.Ask = assertIs<ServeMessage.Ask>(nextNotEvent())

        fun end() {
            line(null)
            runBlocking { withTimeout(30.seconds) { job.join() } }
        }
    }

    private fun session(body: Session.() -> Unit) =
        TempDir.create("ketch-test-").use {
            val session = Session(it.path)
            assertIs<ServeMessage.Hello>(session.next()).also { hello -> assertEquals(PROTOCOL, hello.protocol) }
            session.body()
            if (session.job.isActive) session.end()
        }

    /** A folder a failed uninstall left behind for `ghost`, so uninstalling it asks a question. */
    private fun Session.leftover(): Path {
        val leftover = joinPath(Config.load(root, Report.silent()).storeDir, "ghost")
        createDirAll(joinPath(leftover, "1.0.0.old"))
        return leftover
    }

    private fun result(
        id: Long,
        outcome: Outcome,
    ) = ServeMessage.Result(id, outcome)

    private val ghost = Call.Uninstall(listOf("ghost"))
    private val notFound = Outcome.Error(WireError.NotFound("ghost"))

    @Test
    fun theHelloComesFirstAndEachCallGetsItsResult() =
        session {
            send(AppMessage.Start(1, Call.Root))
            assertEquals(result(1, Outcome.Ok(JsonPrimitive(root.toString()))), nextNotEvent())
            send(AppMessage.Start(2, Call.InstalledPackages))
            assertEquals(result(2, Outcome.Ok(JsonArray(emptyList()))), nextNotEvent())
        }

    @Test
    fun aLineThatIsNoMessageIsRejectedAndTheNextCallStillRuns() =
        session {
            line("{nope")
            assertIs<ServeMessage.Rejected>(next())
            line("""{"type":"call","id":1,"call":{"operation":"nope"}}""")
            assertIs<ServeMessage.Rejected>(next())
            line("")
            send(AppMessage.Start(2, Call.InstalledPackages))
            assertEquals(result(2, Outcome.Ok(JsonArray(emptyList()))), nextNotEvent())
        }

    @Test
    fun aQuestionReachesTheAppAndItsAnswerReachesTheCall() =
        session {
            val leftover = leftover()
            send(AppMessage.Start(1, ghost))
            val ask = ask()
            assertEquals(1, ask.id)
            assertEquals(Question.ConfirmRemoveLeftover(leftover.toString()), ask.question)
            send(AppMessage.Reply(1, ask.questionId, WireAnswer.Confirm(true)))
            assertEquals(result(1, notFound), nextNotEvent())
            assertFalse(isDir(leftover))
        }

    @Test
    fun staleAnswersAreDroppedAndCallsRunOneAtATime() =
        session {
            val leftover = leftover()
            send(AppMessage.Start(1, ghost))
            val ask = ask()
            send(AppMessage.Reply(1, ask.questionId + 1, WireAnswer.Confirm(true)))
            send(AppMessage.Reply(9, ask.questionId, WireAnswer.Confirm(true)))
            send(AppMessage.Start(2, Call.Root))
            send(AppMessage.Start(1, Call.Root))
            assertIs<ServeMessage.Rejected>(nextNotEvent(), "call 1 is still running")
            send(AppMessage.Reply(1, ask.questionId, WireAnswer.Confirm(false)))
            assertEquals(result(1, notFound), nextNotEvent())
            assertEquals(result(2, Outcome.Ok(JsonPrimitive(root.toString()))), nextNotEvent())
            assertTrue(isDir(leftover), "only the answer to the question asked counts")
        }

    @Test
    fun aCancelEndsAQueuedCallAtOnceAndDeclinesAPendingQuestion() =
        session {
            val leftover = leftover()
            send(AppMessage.Start(1, ghost))
            ask()
            send(AppMessage.Start(2, Call.InstalledPackages))
            send(AppMessage.Cancel(2))
            assertEquals(result(2, Outcome.Error(WireError.Cancelled)), nextNotEvent())
            send(AppMessage.Cancel(1))
            assertEquals(1, assertIs<ServeMessage.Result>(nextNotEvent()).id)
            assertTrue(isDir(leftover), "a cancel is no consent")
            send(AppMessage.Cancel(1))
            send(AppMessage.Start(3, Call.Root))
            assertEquals(result(3, Outcome.Ok(JsonPrimitive(root.toString()))), nextNotEvent())
        }

    @Test
    fun theEndOfInputDeclinesWhatWaitsRunsWhatWasSentAndEndsTheServer() =
        session {
            val leftover = leftover()
            send(AppMessage.Start(1, ghost))
            ask()
            send(AppMessage.Start(2, Call.Root))
            send(AppMessage.Start(3, ghost))
            end()
            assertEquals(result(1, notFound), nextNotEvent())
            assertEquals(result(2, Outcome.Ok(JsonPrimitive(root.toString()))), nextNotEvent())
            assertEquals(result(3, notFound), nextNotEvent(), "a question after the end is declined unasked")
            assertTrue(isDir(leftover))
            assertFalse(job.isActive)
        }

    @Test
    fun aCallThatFailsCarriesItsError() =
        session {
            send(AppMessage.Start(2, Call.Prune(emptyList(), -1)))
            assertEquals(result(2, Outcome.Error(WireError.Other("keep must not be negative: -1"))), nextNotEvent())
        }
}
