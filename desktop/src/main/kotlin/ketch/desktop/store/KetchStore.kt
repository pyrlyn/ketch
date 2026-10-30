// The app's one source of state, the port of the macOS and Windows apps' KetchStore. Its functions are called from
// the UI's coroutines, so its own fields need no lock; the core's events and questions arrive on other threads and
// are queued to the operation's coroutine, so they land in the order the core sent them and before its result.
package ketch.desktop.store

import ketch.desktop.core.Callbacks
import ketch.desktop.core.CancelToken
import ketch.desktop.core.Core
import ketch.desktop.core.CoreException
import ketch.desktop.core.describe
import ketch.desktop.core.value
import ketch.desktop.strings.*
import ketch.wire.Answer
import ketch.wire.Call
import ketch.wire.Changelog
import ketch.wire.Check
import ketch.wire.Event
import ketch.wire.InstallOptions
import ketch.wire.KetchError
import ketch.wire.Package
import ketch.wire.PathChange
import ketch.wire.Question
import ketch.wire.Resolution
import ketch.wire.SearchResults
import ketch.wire.Settings
import ketch.wire.Stage
import ketch.wire.TaskKind
import ketch.wire.Upgrade
import ketch.wire.decline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Every screen's way to the core: one small function per thing a screen can ask.
@Suppress("TooManyFunctions")
class KetchStore(
    private val core: Core,
    private val now: () -> Instant = Instant::now,
) {
    private val mutable = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = mutable.asStateFlow()

    /** The version the core said hello with, for About. */
    val coreVersion: String get() = core.version

    private var token: CancelToken? = null
    private var searches = 0
    private var keystrokes = 0
    private var nextLogId = 0L

    /** The running operation's downloads by task id, with the package each one is for. */
    private val downloads = mutableMapOf<Long, String>()

    // Reading

    /** Re-reads installed and outdated packages: on launch, when the window comes forward, after every operation. */
    suspend fun refresh() {
        caught {
            val installed = core.value<List<Package>>(Call.InstalledPackages)
            val outdated = core.value<List<Upgrade>>(Call.Outdated)
            mutable.update { it.copy(installed = installed, outdated = outdated, lastChecked = now()) }
        }?.let { report(it) { refresh() } }
    }

    /** An older, slower search can answer after a newer one; only the newest one's answer is shown. */
    suspend fun search(query: String) {
        val generation = ++searches
        caught {
            val found = core.value<SearchResults>(Call.Search(query, SEARCH_LIMIT))
            if (generation == searches) mutable.update { it.copy(search = found) }
        }?.let { if (generation == searches) report(it) { search(query) } }
    }

    /** Searches once the person has stopped typing for [pause]: a burst of keystrokes asks the core once. */
    suspend fun searchAfterTyping(
        query: String,
        pause: Duration = TYPING_PAUSE,
    ) {
        val keystroke = ++keystrokes
        delay(pause)
        if (keystroke == keystrokes) search(query)
    }

    /**
     * What changed after [from] up to [to], newest first; [to] alone when [from] is null, the installed version when
     * both are. Null when the core could not read it, which [quiet] keeps from an alert: a list's one line of notes is
     * not worth one. A caller cancelled while the core reads gets nothing and no alert: the page that asked has moved on.
     */
    suspend fun changelog(
        name: String,
        from: String?,
        to: String?,
        quiet: Boolean = false,
    ): List<Changelog>? {
        var found: List<Changelog>? = null
        caught {
            found =
                if (from == null) {
                    listOf(core.value<Changelog>(Call.ChangelogOf(name, to)))
                } else {
                    core.value<List<Changelog>>(Call.ChangelogRange(name, from, to))
                }
        }?.let { if (!quiet) report(it, retry = null) }
        return found
    }

    /** ketch's own settings (its root and config.toml), for the Settings screen; null when the core could not say. */
    suspend fun config(): Settings? {
        var found: Settings? = null
        caught { found = core.value<Settings>(Call.Config) }?.let { report(it, retry = null) }
        return found
    }

    /**
     * Shows the package a `ketch://` link names. Nothing a link says installs, upgrades or removes anything: the core
     * checks the link, which is untrusted, and a refusal or an unknown package is an alert like any other.
     */
    suspend fun open(link: String) {
        caught {
            val name = core.value<String>(Call.PackageForLink(link))
            val known =
                state.value.installed.any { it.name == name } ||
                    core.value<SearchResults>(Call.Search(name, SEARCH_LIMIT)).known.any { it.name == name }
            if (!known) throw CoreException(KetchError.NotFound(name))
            mutable.update { it.copy(linked = name) }
        }?.let { report(it, retry = null) }
    }

    fun clearLink() = mutable.update { it.copy(linked = null) }

    suspend fun runDoctor() {
        caught {
            val checks = core.value<List<Check>>(Call.Doctor)
            mutable.update { it.copy(checks = checks) }
        }?.let { report(it) { runDoctor() } }
    }

    /** Repairs what `doctor` can, then asks again: the new report is what says whether it held. */
    suspend fun fixDoctor() {
        caught {
            val fixable =
                state.value.checks
                    .filter { it.fixable }
                    .map { it.name }
            val changes = core.value<List<PathChange>>(Call.DoctorFix)
            val checks = core.value<List<Check>>(Call.Doctor)
            mutable.update { it.copy(checks = checks, fixed = if (changes.isEmpty()) it.fixed else it.fixed + fixable) }
        }?.let { report(it) { fixDoctor() } }
    }

    /**
     * How an install of [name] would choose, or null when its source cannot say. The page says so itself, and a page
     * that is only being read is not worth an alert.
     */
    suspend fun why(name: String): Resolution? {
        var found: Resolution? = null
        caught { found = core.value<Resolution>(Call.Why(name)) }
        return found
    }

    // Operations

    suspend fun install(
        spec: String,
        prerelease: Boolean = false,
    ): Unit = operate(tr(Res.string.store_installing, spec), { install(spec, prerelease) }, Call.Install(listOf(spec), InstallOptions(prerelease = prerelease)))

    /** Upgrades [names], or everything outdated when empty. */
    suspend fun upgrade(names: List<String> = emptyList()): Unit =
        operate(
            if (names.isEmpty()) tr(Res.string.store_upgrading_all) else tr(Res.string.store_upgrading, names.joinToString(", ")),
            { upgrade(names) },
            Call.Upgrade(names),
        )

    suspend fun uninstall(names: List<String>): Unit = operate(tr(Res.string.store_uninstalling, names.joinToString(", ")), { uninstall(names) }, Call.Uninstall(names))

    /** Asks the running operation to stop at its next step, and declines the open question so the core is not left waiting on it. */
    fun cancel() {
        val running = token ?: return
        mutable.update { it.copy(activity = it.activity?.copy(isCancelling = true)) }
        running.cancel()
        decline()
    }

    /** Runs again what the held lock refused. The banner goes now and comes back with the new holder if the lock is still held. */
    suspend fun retryBusy() {
        val busy = state.value.busy ?: return
        mutable.update { it.copy(busy = null) }
        busy.retry()
    }

    /** Answers the pending question. */
    fun answer(answer: Answer) {
        val pending = state.value.pending ?: return
        mutable.update { it.copy(pending = null) }
        pending.reply.complete(answer)
    }

    /** Declines the pending question: no binary chosen, nothing stopped, nothing removed. */
    fun decline() {
        state.value.pending?.let { answer(decline(it.question)) }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    // Plumbing

    private suspend fun operate(
        title: String,
        retry: suspend () -> Unit,
        call: Call,
    ) {
        if (token != null) {
            mutable.update { it.copy(error = tr(Res.string.store_busy)) }
            return
        }
        val cancel = CancelToken().also { token = it }
        mutable.update { it.copy(activity = Activity(title), busy = null) }
        log(LogLevel.Info, title)
        val error =
            try {
                coroutineScope { perform(call, cancel) }
            } finally {
                token = null
                downloads.clear()
                decline()
                mutable.update { it.copy(activity = null) }
            }
        if (error == null) log(LogLevel.Success, tr(Res.string.store_done, title)) else report(error, retry)
        refresh()
    }

    private suspend fun CoroutineScope.perform(
        call: Call,
        cancel: CancelToken,
    ): KetchError? {
        val inbox = Channel<Item>(Channel.UNLIMITED)
        val drain = launch { for (item in inbox) take(item, cancel) }
        return try {
            caught { core.call(call, Inbox(inbox), cancel) }
        } finally {
            inbox.close()
            drain.join()
        }
    }

    private fun take(
        item: Item,
        cancel: CancelToken,
    ) {
        when (item) {
            is Item.Said -> {
                apply(item.event)
            }

            is Item.Asked -> {
                // A cancel declined what was open; what the core asks after it gets the same answer.
                if (cancel.isCancelled) {
                    item.reply.complete(decline(item.question))
                } else {
                    decline()
                    mutable.update { it.copy(pending = Pending(item.question, item.reply)) }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per event the core sends; split, it would read worse.
    private fun apply(event: Event) {
        when (event) {
            is Event.Step -> {
                progress(event.pkg) { it?.copy(stage = event.stage) ?: PackageProgress(event.stage) }
                log(LogLevel.Info, "${event.pkg}: ${event.stage.name.lowercase()}")
            }

            is Event.Began -> {
                began(event)
            }

            is Event.Sized -> {
                downloads[event.id]?.let { pkg -> progress(pkg) { downloading(it).copy(total = event.total) } }
            }

            is Event.Progress -> {
                downloads[event.id]?.let { pkg -> progress(pkg) { downloading(it).copy(done = event.done, total = event.total) } }
            }

            is Event.Ended -> {
                downloads.remove(event.id)
            }

            is Event.Abandoned -> {
                downloads.remove(event.id)
            }

            is Event.Status -> {
                status("${event.verb} ${event.detail}")
            }

            is Event.Success -> {
                log(LogLevel.Success, "${event.verb} ${event.detail}")
            }

            is Event.Warn -> {
                log(LogLevel.Warning, event.detail)
            }

            is Event.Note -> {
                log(LogLevel.Info, event.detail)
            }

            is Event.Debug -> {
                // The CLI shows these only under --verbose; the log is for people, not for debugging the core.
            }
        }
    }

    private fun began(event: Event.Began) {
        when (val task = event.task) {
            // The core labels a download with the name its package's steps use.
            is TaskKind.Download -> downloads[event.id] = task.label

            is TaskKind.Activity -> status(task.message)

            TaskKind.Batch, is TaskKind.Counter -> Unit
        }
    }

    private fun progress(
        pkg: String,
        change: (PackageProgress?) -> PackageProgress,
    ) = mutable.update { state ->
        state.copy(activity = state.activity?.let { it.copy(packages = it.packages + (pkg to change(it.packages[pkg]))) })
    }

    private fun status(message: String) {
        mutable.update { it.copy(activity = it.activity?.copy(status = message)) }
        log(LogLevel.Info, message)
    }

    /** The lock held is a banner with Retry, a cancel only a log line, anything else an alert in the core's wording. */
    private fun report(
        error: KetchError,
        retry: (suspend () -> Unit)?,
    ) {
        val message = describe(error)
        when (error) {
            is KetchError.Busy -> {
                log(LogLevel.Warning, message)
                retry?.let { mutable.update { state -> state.copy(busy = Busy(error.pid, it)) } }
            }

            KetchError.Cancelled -> {
                log(LogLevel.Warning, tr(Res.string.store_cancelled))
            }

            else -> {
                log(LogLevel.Error, message)
                mutable.update { it.copy(error = message) }
            }
        }
    }

    private fun log(
        level: LogLevel,
        message: String,
    ) = mutable.update { it.copy(log = (it.log + LogEntry(++nextLogId, now(), level, message)).takeLast(LOG_LIMIT)) }

    /** What the core said while a call ran, queued for the operation's coroutine. */
    private sealed interface Item {
        class Said(
            val event: Event,
        ) : Item

        class Asked(
            val question: Question,
            val reply: CompletableDeferred<Answer>,
        ) : Item
    }

    private class Inbox(
        private val items: SendChannel<Item>,
    ) : Callbacks {
        override fun event(event: Event) {
            items.trySend(Item.Said(event))
        }

        // A question that comes after the call ended has nobody left to answer it.
        override suspend fun ask(question: Question): Answer {
            val reply = CompletableDeferred<Answer>()
            return if (items.trySend(Item.Asked(question, reply)).isSuccess) reply.await() else decline(question)
        }
    }

    companion object {
        const val LOG_LIMIT = 500
        const val SEARCH_LIMIT = 50
        val TYPING_PAUSE = 250.milliseconds

        /** The core's error, or null when [body] ran; a value the app cannot read is an error too, not a crash. */
        private inline fun caught(body: () -> Unit): KetchError? =
            try {
                body()
                null
            } catch (e: CoreException) {
                e.error
            } catch (e: SerializationException) {
                KetchError.Other(tr(Res.string.store_bad_value, e.message.orEmpty()))
            }
    }
}

private fun downloading(progress: PackageProgress?) = progress ?: PackageProgress(Stage.Downloading)
