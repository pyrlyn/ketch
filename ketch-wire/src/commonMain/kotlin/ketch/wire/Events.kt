package ketch.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * What the core reports while a call runs, the port of `ketch-ffi`'s `callbacks.rs`. Task ids are unique for the
 * `ketch serve` process, so the app can match a progress event to the bar its `began` opened.
 */

/** Which stage of the install pipeline a package has reached. */
@Serializable
enum class Stage {
    @SerialName("resolving")
    Resolving,

    @SerialName("downloading")
    Downloading,

    @SerialName("verifying")
    Verifying,

    @SerialName("extracting")
    Extracting,

    @SerialName("trusting")
    Trusting,

    @SerialName("installing")
    Installing,
}

/** What a task that reports progress is. */
@Serializable
sealed interface TaskKind {
    /** A batch of downloads, which the downloads in it name by [Download.batchId]. */
    @Serializable
    @SerialName("batch")
    data object Batch : TaskKind

    @Serializable
    @SerialName("download")
    data class Download(
        val label: String,
        @SerialName("batch_id") val batchId: Long?,
    ) : TaskKind

    /** Work with no size, shown as a spinner. */
    @Serializable
    @SerialName("activity")
    data class Activity(
        val message: String,
    ) : TaskKind

    /** Counted work, such as `checked 3 of 12 packages`. */
    @Serializable
    @SerialName("counter")
    data class Counter(
        val verb: String,
        val unit: String,
        val total: Long,
    ) : TaskKind
}

/** One thing the core said. */
@Serializable
sealed interface Event {
    @Serializable
    @SerialName("step")
    data class Step(
        @SerialName("package") val pkg: String,
        val stage: Stage,
    ) : Event

    @Serializable
    @SerialName("status")
    data class Status(
        val verb: String,
        val detail: String,
    ) : Event

    @Serializable
    @SerialName("success")
    data class Success(
        val verb: String,
        val detail: String,
    ) : Event

    @Serializable
    @SerialName("warn")
    data class Warn(
        val detail: String,
    ) : Event

    @Serializable
    @SerialName("note")
    data class Note(
        val detail: String,
    ) : Event

    @Serializable
    @SerialName("debug")
    data class Debug(
        val detail: String,
    ) : Event

    @Serializable
    @SerialName("began")
    data class Began(
        val id: Long,
        val task: TaskKind,
    ) : Event

    /** A download learned its file name and, when the server said, its size. */
    @Serializable
    @SerialName("sized")
    data class Sized(
        val id: Long,
        val name: String,
        val total: Long?,
    ) : Event

    @Serializable
    @SerialName("progress")
    data class Progress(
        val id: Long,
        val done: Long,
        val total: Long?,
    ) : Event

    @Serializable
    @SerialName("ended")
    data class Ended(
        val id: Long,
        val message: String?,
    ) : Event

    /** The task stopped without finishing, through an error or a cancel. */
    @Serializable
    @SerialName("abandoned")
    data class Abandoned(
        val id: Long,
    ) : Event
}

/** A process holding a file an upgrade is about to replace. */
@Serializable
data class Holder(
    val pid: Int,
    val path: String,
)

/** A question the core puts to the app mid-call. */
@Serializable
sealed interface Question {
    /** Which of [candidates], files of [pkg] sharing its name, to link. */
    @Serializable
    @SerialName("choose_binary")
    data class ChooseBinary(
        @SerialName("package") val pkg: String,
        val candidates: List<String>,
    ) : Question

    /** Whether to stop [holders] so their files can be replaced. */
    @Serializable
    @SerialName("stop_processes")
    data class StopProcesses(
        val holders: List<Holder>,
    ) : Question

    /** Whether to remove [path], a package folder an earlier uninstall left behind with nothing recorded in it. */
    @Serializable
    @SerialName("confirm_remove_leftover")
    data class ConfirmRemoveLeftover(
        val path: String,
    ) : Question
}

/** What the app answers: the picked index (null declines), or yes or no. */
@Serializable
sealed interface Answer {
    @Serializable
    @SerialName("index")
    data class Index(
        val index: Int?,
    ) : Answer

    @Serializable
    @SerialName("confirm")
    data class Confirm(
        val confirmed: Boolean,
    ) : Answer
}

/** Why a call failed. */
@Serializable
sealed interface KetchError {
    /**
     * Another operation holds the install tree's lock: a ketch process named by [pid], or nobody known when the
     * lock file could not be read. Retry once it finishes.
     */
    @Serializable
    @SerialName("busy")
    data class Busy(
        val pid: Int?,
    ) : KetchError

    /** The call was cancelled; nothing was left half-installed. */
    @Serializable
    @SerialName("cancelled")
    data object Cancelled : KetchError

    /** No installed package, or no release, answers to [name]. */
    @Serializable
    @SerialName("not_found")
    data class NotFound(
        val name: String,
    ) : KetchError

    /** A source could not be reached, or answered with an HTTP error. */
    @Serializable
    @SerialName("network")
    data class Network(
        val message: String,
    ) : KetchError

    /** A download did not match its published or recorded checksum, or none was published where one was required. */
    @Serializable
    @SerialName("verification")
    data class Verification(
        val message: String,
    ) : KetchError

    /** Anything else, worded as the CLI would word it. */
    @Serializable
    @SerialName("other")
    data class Other(
        val message: String,
    ) : KetchError
}
