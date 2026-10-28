// `ketch serve`: the desktop app's way into the core, one JSON object per line each way (see `:ketch-wire`'s
// Protocol.kt). The Rust app links ketch-ffi instead; a JVM app cannot link a Kotlin/Native core, so it runs this.
//
// Input is read apart from the calls, so an answer or a cancel reaches a call that is running. The end of input is
// only that: what was sent still runs, and a question nobody is left to answer is declined; stopping work is what
// `cancel` is for, so `ketch serve < calls.jsonl` does what the file says. Calls run one at a time in arrival order: the core was not written for two operations at once in one process (T8's kotlinx-io
// `basename` race), and the lock file would make most of them wait for each other anyway.
package ketch.serve

import ketch.core.cancel.Cancel
import ketch.ui.SpinLock
import ketch.wire.AppMessage
import ketch.wire.Call
import ketch.wire.Outcome
import ketch.wire.PROTOCOL
import ketch.wire.Question
import ketch.wire.ServeMessage
import ketch.wire.Wire
import ketch.wire.decline
import ketch.wire.resultSerializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import ketch.wire.Answer as WireAnswer
import ketch.wire.KetchError as WireError

/** Serves [core] over [lines] (null at end of input) and [write] (one line, no newline) until input ends. */
class Server(
    private val core: KetchCore,
    private val lines: () -> String?,
    private val write: (String) -> Unit,
) {
    private val outbox = Channel<ServeMessage>(Channel.UNLIMITED)
    private val queue = Channel<Running>(Channel.UNLIMITED)
    private val guard = SpinLock()
    private val calls = mutableMapOf<Long, Running>()
    private var nextQuestion = 1L
    private var closed = false

    /** Runs until input has ended and every call sent before that has its result. */
    suspend fun serve() =
        coroutineScope {
            // Messages leave in the order they are sent, whichever thread sends them; one coroutine owns stdout.
            val writer = launch(Dispatchers.IO) { for (message in outbox) write(Wire.json.encodeToString(ServeMessage.serializer(), message)) }
            send(ServeMessage.Hello(PROTOCOL, ketchVersion()))
            val worker = launch(Dispatchers.IO) { for (call in queue) call.run() }
            val input = Channel<String?>(Channel.UNLIMITED)
            launch(Dispatchers.IO) {
                do {
                    val line = lines()
                    input.send(line)
                } while (line != null)
            }
            for (line in input) {
                if (line == null) break
                if (line.isNotBlank()) receive(line)
            }
            guard
                .locked {
                    closed = true
                    calls.values.toList()
                }.forEach { it.decline() }
            queue.close()
            worker.join()
            outbox.close()
            writer.join()
        }

    private fun send(message: ServeMessage) {
        outbox.trySend(message)
    }

    private fun receive(line: String) {
        val message =
            try {
                Wire.json.decodeFromString(AppMessage.serializer(), line)
            } catch (e: SerializationException) {
                send(ServeMessage.Rejected(e.message ?: "unreadable message"))
                return
            } catch (e: IllegalArgumentException) {
                send(ServeMessage.Rejected(e.message ?: "unreadable message"))
                return
            }
        when (message) {
            is AppMessage.Start -> start(message)

            // An answer or a cancel for a call that has ended crossed its result on the way; nothing waits for it.
            is AppMessage.Reply -> guard.locked { calls[message.id] }?.answer(message.questionId, message.answer)

            is AppMessage.Cancel -> guard.locked { calls[message.id] }?.cancel()
        }
    }

    private fun start(message: AppMessage.Start) {
        val running = Running(message.id, message.call)
        val taken =
            guard.locked {
                (message.id in calls).also { if (!it) calls[message.id] = running }
            }
        if (taken) {
            send(ServeMessage.Rejected("call ${message.id} is still running"))
            return
        }
        queue.trySend(running)
    }

    /** One call from arrival to result, and the questions it is waiting on. */
    private inner class Running(
        val id: Long,
        val call: Call,
    ) {
        private val token = Cancel()
        private var begun = false
        private val waiting = mutableMapOf<Long, Pair<Question, CompletableDeferred<WireAnswer>>>()

        fun run() {
            // Cancelled while queued: its result went out then.
            if (!guard.locked { (!token.isCancelled).also { begun = it } }) return
            val outcome = outcome()
            send(ServeMessage.Result(id, outcome))
            guard.locked { calls.remove(id) }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun outcome(): Outcome =
            try {
                @Suppress("UNCHECKED_CAST")
                val serializer = resultSerializer(call) as KSerializer<Any?>
                Outcome.Ok(Wire.json.encodeToJsonElement(serializer, dispatch(core, call, ::report, ::ask, token)))
            } catch (e: Exception) {
                // A call's failure is its result; the next call must still run.
                Outcome.Error(e.toWire())
            }

        private fun report(event: ketch.wire.Event) = send(ServeMessage.Reported(id, event))

        /** Put [question] to the app and wait for its answer; a cancel, or the end of input, is a decline. */
        private fun ask(question: Question): WireAnswer {
            val answer = CompletableDeferred<WireAnswer>()
            val asked =
                guard.locked {
                    if (token.isCancelled || closed) {
                        null
                    } else {
                        nextQuestion++.also { waiting[it] = question to answer }
                    }
                } ?: return decline(question)
            send(ServeMessage.Ask(id, asked, question))
            return runBlocking { answer.await() }
        }

        fun answer(
            question: Long,
            answer: WireAnswer,
        ) {
            guard.locked { waiting.remove(question) }?.second?.complete(answer)
        }

        fun cancel() {
            val queued =
                guard.locked {
                    token.cancel()
                    (!begun).also { if (it) calls.remove(id) }
                }
            decline()
            // A call that never began has no other way to end; one that runs sees the token at its next check.
            if (queued) send(ServeMessage.Result(id, Outcome.Error(WireError.Cancelled)))
        }

        /** Answer every question still waiting with a decline. */
        fun decline() {
            val pending =
                guard.locked {
                    waiting.values.toList().also { waiting.clear() }
                }
            pending.forEach { (question, answer) -> answer.complete(decline(question)) }
        }
    }
}

/** Runs [call] on [core]; the value is what [resultSerializer] says the call returns. */
// One branch per call is a table, not branching logic; splitting it would hide which call is missing.
@Suppress("CyclomaticComplexMethod")
internal fun dispatch(
    core: KetchCore,
    call: Call,
    listener: Listener,
    asker: Asker,
    cancel: Cancel,
): Any? =
    when (call) {
        Call.Root -> core.root()
        Call.InstalledPackages -> core.installed()
        is Call.Search -> core.search(call.query, call.limit, listener, cancel)
        Call.Outdated -> core.outdated(listener, cancel)
        is Call.Install -> core.install(call.specs, call.options, listener, asker, cancel)
        is Call.Upgrade -> core.upgrade(call.names, listener, asker, cancel)
        is Call.Uninstall -> core.uninstall(call.names, listener, asker, cancel)
        is Call.ChangelogOf -> core.changelog(call.pkg, call.version, listener)
        is Call.ChangelogRange -> core.changelogRange(call.pkg, call.from, call.to, listener)
        Call.Doctor -> core.doctor(listener)
        Call.DoctorFix -> core.doctorFix(listener)
        is Call.History -> core.history(call.pkg, call.limit)
        is Call.Info -> core.info(call.pkg, listener)
        is Call.Pin -> core.pin(call.names)
        is Call.Unpin -> core.unpin(call.names)
        is Call.Rollback -> core.rollback(call.pkg, call.to, listener, asker)
        is Call.Prune -> core.prune(call.names, call.keep, listener)
        Call.RegistryRefresh -> core.registryRefresh(listener, cancel)
        Call.PathStatusOf -> core.pathStatus()
        is Call.PathInstall -> core.pathInstall(call.dryRun, listener)
        Call.Config -> core.config()
        is Call.PackageForLink -> packageForLink(call.url)
    }
