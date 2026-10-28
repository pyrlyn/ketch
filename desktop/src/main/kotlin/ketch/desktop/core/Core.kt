// The core as the app sees it: one suspending call per `ketch serve` operation, with its events, questions and
// cancel. The store and the screens see only this, so they run as well on FakeCore as on ServeCore.
package ketch.desktop.core

import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Event
import ketch.wire.KetchError
import ketch.wire.Question
import ketch.wire.Wire
import ketch.wire.resultSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/** What one call reports while it runs, and the questions it puts to the person. */
interface Callbacks {
    /** Called in the order the core sent them, off the UI thread. */
    fun event(event: Event) {}

    /** The call waits for the answer; the default declines. */
    suspend fun ask(question: Question): Answer = decline(question)

    companion object {
        val None = object : Callbacks {}
    }
}

/** What declining [question] answers: no binary chosen, nothing confirmed. */
fun decline(question: Question): Answer =
    when (question) {
        is Question.ChooseBinary -> Answer.Index(null)
        is Question.StopProcesses, is Question.ConfirmRemoveLeftover -> Answer.Confirm(false)
    }

/** The core refused or failed a call; [error] is the core's own. */
class CoreException(
    val error: KetchError,
    cause: Throwable? = null,
) : Exception(describe(error), cause)

/** The wording the Rust apps show for each error. */
fun describe(error: KetchError): String =
    when (error) {
        is KetchError.Busy -> error.pid?.let { "ketch is running in another process (pid $it)." } ?: "ketch is running in another process."
        KetchError.Cancelled -> "Cancelled."
        is KetchError.NotFound -> "No package named ${error.name}."
        is KetchError.Network -> "Network error: ${error.message}"
        is KetchError.Verification -> "Verification failed: ${error.message}"
        is KetchError.Other -> error.message
    }

/** Asks a running call to stop at its next check. Cancelling twice is cancelling once. */
class CancelToken {
    private val handlers = mutableListOf<() -> Unit>()
    private var cancelled = false

    val isCancelled: Boolean get() = synchronized(handlers) { cancelled }

    fun cancel() {
        val run =
            synchronized(handlers) {
                if (cancelled) return
                cancelled = true
                handlers.toList().also { handlers.clear() }
            }
        run.forEach { it() }
    }

    /** Runs [handler] once when the token is cancelled, or now if it already is. */
    fun onCancel(handler: () -> Unit) {
        val now =
            synchronized(handlers) {
                cancelled.also { if (!it) handlers += handler }
            }
        if (now) handler()
    }
}

interface Core : AutoCloseable {
    /** The core's version, as `ketch --version` prints it after the name. */
    val version: String

    /** Runs [call] and returns its value as JSON; throws [CoreException] with the core's error. */
    suspend fun call(
        call: Call,
        callbacks: Callbacks = Callbacks.None,
        cancel: CancelToken = CancelToken(),
    ): JsonElement
}

/** As [Core.call], with the value read as the type the call returns. */
suspend fun <T> Core.value(
    call: Call,
    callbacks: Callbacks = Callbacks.None,
    cancel: CancelToken = CancelToken(),
): T {
    @Suppress("UNCHECKED_CAST")
    val serializer = resultSerializer(call) as KSerializer<T>
    return Wire.json.decodeFromJsonElement(serializer, call(call, callbacks, cancel))
}
