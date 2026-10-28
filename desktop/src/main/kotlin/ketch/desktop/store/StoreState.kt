// What the screens show, as one immutable snapshot: the store replaces it whole, so a screen never sees half of an
// event applied.
package ketch.desktop.store

import ketch.desktop.core.describe
import ketch.wire.Answer
import ketch.wire.Check
import ketch.wire.CheckOutcome
import ketch.wire.KetchError
import ketch.wire.Package
import ketch.wire.Question
import ketch.wire.SearchResults
import ketch.wire.Stage
import ketch.wire.Upgrade
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import kotlin.math.min

/** How far one package of the running operation has come. */
data class PackageProgress(
    val stage: Stage,
    val done: Long = 0,
    val total: Long? = null,
) {
    /** 0..1 across all stages, with the download filling its own share as the bytes arrive. */
    val fraction: Double
        get() {
            val within = if (stage == Stage.Downloading && total != null && total > 0) done.toDouble() / total else 0.0
            return min(1.0, (stage.ordinal + within) / Stage.entries.size)
        }
}

/** The operation that runs, as the Activity pane and the tray show it. */
data class Activity(
    val title: String,
    val packages: Map<String, PackageProgress> = emptyMap(),
    val status: String? = null,
    val isCancelling: Boolean = false,
) {
    /** The mean over the packages seen so far. */
    val fraction: Double get() = if (packages.isEmpty()) 0.0 else packages.values.sumOf { it.fraction } / packages.size
}

enum class LogLevel { Info, Success, Warning, Error }

/** One line of the Activity log. */
data class LogEntry(
    val id: Long,
    val at: Instant,
    val level: LogLevel,
    val message: String,
)

/** Another process holds the ketch lock; [retry] runs again what it refused. */
class Busy internal constructor(
    val pid: Long?,
    internal val retry: suspend () -> Unit,
) {
    val message: String get() = describe(KetchError.Busy(pid))
}

/** A question the running operation waits on: which binary, whether to stop processes, whether to remove a leftover. */
class Pending internal constructor(
    val question: Question,
    internal val reply: CompletableDeferred<Answer>,
)

data class StoreState(
    val installed: List<Package> = emptyList(),
    val outdated: List<Upgrade> = emptyList(),
    val search: SearchResults = SearchResults(emptyList(), emptyList()),
    val checks: List<Check> = emptyList(),
    val activity: Activity? = null,
    val log: List<LogEntry> = emptyList(),
    /** Set when the core refused because another process holds the lock. */
    val busy: Busy? = null,
    /** An error to show once, in the core's wording; the screen dismisses it. */
    val error: String? = null,
    val pending: Pending? = null,
    /** A package a `ketch://` link asked to show; the window opens its page and clears it. */
    val linked: String? = null,
    val lastChecked: Instant? = null,
) {
    val isRunning: Boolean get() = activity != null

    /** Newer releases `upgrade` installs: what no pin and no `ketch.lock` holds back. */
    val updates: List<Upgrade> get() = outdated.filter { !it.isHeld }

    /** Newer releases a pin or a `ketch.lock` holds back, shown but never upgraded. */
    val held: List<Upgrade> get() = outdated.filter { it.isHeld }

    /** What the sidebar, the tray and Upgrade all count. */
    val pendingUpgradeCount: Int get() = updates.size

    /** Doctor checks that are not ok, for the sidebar. */
    val problemCount: Int get() = checks.count { it.outcome != CheckOutcome.Ok }

    /** The version an upgrade would move [name] to; null when it is current or held. */
    fun outdatedVersion(name: String): String? = updates.firstOrNull { it.name == name }?.latest
}

// The Rust apps look only at `held_by`; this wire also carries `pinned`, and `upgrade` skips either.
private val Upgrade.isHeld: Boolean get() = pinned || heldBy != null
