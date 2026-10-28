// The real core: the native `ketch serve` as a child process, one JSON object per line each way.
//
// One reader owns the child's stdout and routes each message to its call by id; a question is answered on its own
// coroutine, so a call waiting on the person never holds up another call's events. What the child writes is
// untrusted input: a line that is no message is skipped rather than trusted, and a child that dies fails every open
// call with what it printed on stderr instead of leaving them waiting.
package ketch.desktop.core

import ketch.wire.AppMessage
import ketch.wire.Call
import ketch.wire.KetchError
import ketch.wire.Outcome
import ketch.wire.PROTOCOL
import ketch.wire.ServeMessage
import ketch.wire.Wire
import ketch.wire.decline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The `ketch` the app runs: `KETCH_EXE` when set, else the one packaged in the app's resources. */
fun locateKetch(
    env: Map<String, String> = System.getenv(),
    resources: String? = System.getProperty("compose.application.resources.dir"),
): Path? {
    env["KETCH_EXE"]?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
    val name = if (System.getProperty("os.name").startsWith("Windows")) "ketch.exe" else "ketch"
    return resources?.let { Path.of(it, name) }?.takeIf(Files::isExecutable)
}

class ServeCore private constructor(
    private val process: Process,
    private val reader: BufferedReader,
    override val version: String,
) : Core {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer: Writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val calls = ConcurrentHashMap<Long, Open>()
    private val ids = AtomicLong()
    private val stderr = Tail()

    @Volatile
    private var ended: KetchError? = null

    private class Open(
        val callbacks: Callbacks,
        val outcome: CompletableDeferred<Outcome> = CompletableDeferred(),
    )

    private fun begin() {
        val drained = scope.launch { stderr.drain(process.errorStream.bufferedReader(Charsets.UTF_8)) }
        scope.launch { route(drained) }
    }

    override suspend fun call(
        call: Call,
        callbacks: Callbacks,
        cancel: CancelToken,
    ): JsonElement {
        val id = ids.incrementAndGet()
        val open = Open(callbacks)
        calls[id] = open
        // A child that ended after the check would have failed the call already; one that ended before never will.
        ended?.let { calls.remove(id)?.outcome?.complete(Outcome.Error(it)) }
        send(AppMessage.Start(id, call))
        cancel.onCancel { send(AppMessage.Cancel(id)) }
        val outcome =
            try {
                open.outcome.await()
            } catch (e: CancellationException) {
                // Nobody waits for this result any more; the core still stops at its next check.
                send(AppMessage.Cancel(id))
                throw e
            }
        return when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Error -> throw CoreException(outcome.error)
        }
    }

    private fun send(message: AppMessage) {
        val line = Wire.json.encodeToString(AppMessage.serializer(), message)
        try {
            synchronized(writer) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        } catch (_: IOException) {
            // The child is gone; the reader sees its end and fails what is open.
        }
    }

    private suspend fun route(drained: Job) {
        while (true) {
            val line = reader.readLine() ?: break
            when (val message = decode(line)) {
                is ServeMessage.Reported -> {
                    calls[message.id]?.callbacks?.event(message.event)
                }

                is ServeMessage.Ask -> {
                    answer(message)
                }

                is ServeMessage.Result -> {
                    calls.remove(message.id)?.outcome?.complete(message.outcome)
                }

                // Only a bug on one side makes the child reject a line; failing every call says so, waiting would not.
                is ServeMessage.Rejected -> {
                    end(KetchError.Other("ketch serve rejected a message: ${message.message}"))
                }

                is ServeMessage.Hello, null -> {}
            }
        }
        process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
        // The last of stderr says why it stopped, and may still be in the pipe.
        withTimeoutOrNull(DRAIN_MILLIS) { drained.join() }
        val code = if (process.isAlive) "still running" else "exit ${process.exitValue()}"
        end(KetchError.Other(listOf("ketch serve stopped ($code)", stderr.text()).filter { it.isNotBlank() }.joinToString("\n")))
    }

    private fun answer(ask: ServeMessage.Ask) {
        val open = calls[ask.id] ?: return
        scope.launch {
            val answer =
                try {
                    open.callbacks.ask(ask.question)
                } catch (e: CancellationException) {
                    send(AppMessage.Reply(ask.id, ask.questionId, decline(ask.question)))
                    throw e
                }
            send(AppMessage.Reply(ask.id, ask.questionId, answer))
        }
    }

    private fun end(error: KetchError) {
        ended = error
        calls.keys.toList().forEach { id -> calls.remove(id)?.outcome?.complete(Outcome.Error(error)) }
    }

    /** Stops what runs, lets the child end on its own for a moment, then kills it. */
    override fun close() {
        calls.keys.forEach { send(AppMessage.Cancel(it)) }
        try {
            writer.close()
        } catch (_: IOException) {
            // Already closed by a child that ended.
        }
        if (!process.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        scope.cancel()
    }

    companion object {
        private const val EXIT_WAIT_SECONDS = 5L
        private const val DRAIN_MILLIS = 500L

        /** Starts `ketch serve` over [root] (the core's default when null) and waits for its hello. */
        suspend fun start(
            exe: Path,
            root: Path? = null,
            env: Map<String, String> = emptyMap(),
        ): ServeCore =
            withContext(Dispatchers.IO) {
                val args = listOf(exe.toString(), "serve") + (root?.let { listOf("--root", it.toString()) } ?: emptyList())
                val process =
                    try {
                        ProcessBuilder(args).apply { environment().putAll(env) }.start()
                    } catch (e: IOException) {
                        throw CoreException(KetchError.Other("could not run $exe: ${e.message}"), e)
                    }
                val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
                val hello = reader.readLine()?.let(::decode) as? ServeMessage.Hello
                if (hello == null || hello.protocol != PROTOCOL) {
                    val why = hello?.let { "it speaks protocol ${it.protocol}, this app $PROTOCOL" } ?: "it did not say hello"
                    // A child that ended has said why on stderr; one still running may never close it.
                    val exited = process.waitFor(DRAIN_MILLIS, TimeUnit.MILLISECONDS)
                    val err =
                        if (exited) {
                            process.errorStream
                                .bufferedReader()
                                .readText()
                                .trim()
                        } else {
                            ""
                        }
                    process.destroyForcibly()
                    throw CoreException(KetchError.Other(listOf("$exe cannot serve this app: $why", err).filter { it.isNotEmpty() }.joinToString("\n")))
                }
                ServeCore(process, reader, hello.version).also { it.begin() }
            }

        private fun decode(line: String): ServeMessage? =
            try {
                Wire.json.decodeFromString(ServeMessage.serializer(), line)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

/** The last few kilobytes of the child's stderr: enough to say why it stopped, never all of it. */
private class Tail {
    private val text = StringBuilder()

    fun drain(from: BufferedReader) {
        try {
            from.forEachLine { line ->
                synchronized(text) {
                    text.append(line).append('\n')
                    if (text.length > LIMIT) text.delete(0, text.length - LIMIT)
                }
            }
        } catch (_: IOException) {
            // The child closed it; what came before is kept.
        }
    }

    fun text(): String = synchronized(text) { text.toString().trim() }

    companion object {
        const val LIMIT = 4096
    }
}
