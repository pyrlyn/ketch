package ketch.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The JSON-lines protocol between `ketch serve` and the desktop app: one message per line, each way. The binary
 * opens with `hello`; the app sends calls, each with an id of its choosing, and the binary answers every call with
 * exactly one `result`, after any `event` and `ask` lines for it. Calls run one at a time, in the order they arrive.
 */

/** Bumped whenever a record or message changes shape. */
const val PROTOCOL = 1

/** The one JSON setup both sides use: every field written, nulls included, as the scenarios have them. */
object Wire {
    val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }
}

/** How a call ended: the value it returned, read with [resultSerializer], or the error. */
@Serializable
sealed interface Outcome {
    @Serializable
    @SerialName("ok")
    data class Ok(
        val value: JsonElement,
    ) : Outcome

    @Serializable
    @SerialName("error")
    data class Error(
        val error: KetchError,
    ) : Outcome
}

/** What `ketch serve` writes. */
@Serializable
sealed interface ServeMessage {
    /** The first line, so the app can refuse a binary that speaks another [protocol]. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val version: String,
    ) : ServeMessage

    /** Something call [id] reported. */
    @Serializable
    @SerialName("event")
    data class Reported(
        val id: Long,
        val event: Event,
    ) : ServeMessage

    /** Call [id] waits for the app's answer to [question], which names [questionId]. */
    @Serializable
    @SerialName("ask")
    data class Ask(
        val id: Long,
        @SerialName("question_id") val questionId: Long,
        val question: Question,
    ) : ServeMessage

    /** Call [id] is over. */
    @Serializable
    @SerialName("result")
    data class Result(
        val id: Long,
        val outcome: Outcome,
    ) : ServeMessage

    /** A line the binary could not take: not a message, or a call whose id is already running or queued. */
    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val message: String,
    ) : ServeMessage
}

/** What the app writes. */
@Serializable
sealed interface AppMessage {
    @Serializable
    @SerialName("call")
    data class Start(
        val id: Long,
        val call: Call,
    ) : AppMessage

    /** The answer to question [questionId] of call [id]. One for a question no longer asked is dropped. */
    @Serializable
    @SerialName("answer")
    data class Reply(
        val id: Long,
        @SerialName("question_id") val questionId: Long,
        val answer: Answer,
    ) : AppMessage

    /** Stop call [id], running or queued; its result is then `cancelled` unless it finished first. */
    @Serializable
    @SerialName("cancel")
    data class Cancel(
        val id: Long,
    ) : AppMessage
}
