// Which upgrades are worth a notice, what it says, and the periodic check that posts it. The rule is pure so a test can
// pin it; the notifier owns everything that touches the system.
package ketch.desktop.store

import ketch.desktop.settings.AppSettings
import ketch.desktop.strings.*
import ketch.wire.Upgrade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object UpdateNotices {
    private const val LISTED = 3

    /** An upgrade is its package and target version: a package that moves on to a newer release is news again. */
    fun key(upgrade: Upgrade) = "${upgrade.name}@${upgrade.latest}"

    /** The upgrades [notified] has not announced. Held ones never count: `upgrade` would leave them where they are. */
    fun fresh(
        upgrades: List<Upgrade>,
        notified: Set<String>,
    ) = upgrades.filter { !it.pinned && it.heldBy == null && key(it) !in notified }

    /** The notice's title and body; versions come from release tags a project's author wrote. */
    fun message(
        upgrades: List<Upgrade>,
        listed: Int = LISTED,
    ): Pair<String, String> {
        val title = plural(upgrades.size, Res.plurals.updates_available)
        val shown = upgrades.take(listed).map { printable("${it.name} ${it.installed} → ${it.latest}") }
        val more = if (upgrades.size > listed) listOf(tr(Res.string.notice_more, upgrades.size - listed)) else emptyList()
        return title to (shown + more).joinToString("\n")
    }

    /** Drops control and format characters, the bidi overrides among them. */
    fun printable(text: String): String =
        buildString {
            text.codePoints().forEach { point ->
                val type = Character.getType(point)
                if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()) appendCodePoint(point)
            }
        }
}

/** The system's notices, so tests need no permission dialog. */
interface UpdateNotifier {
    /** Asks the system to let the app post; true when it may. */
    suspend fun requestAuthorization(): Boolean

    fun post(
        title: String,
        body: String,
    )
}

class UpdateChecks(
    private val store: KetchStore,
    private val settings: AppSettings,
    private val notifier: UpdateNotifier,
) {
    /** Turns notices on or off. The system is asked only when they go on; a refusal leaves them off and says why. */
    suspend fun setNotifications(enabled: Boolean): String? {
        val allowed = enabled && notifier.requestAuthorization()
        settings.update { it.copy(notifies = allowed) }
        return if (enabled && !allowed) REFUSED else null
    }

    /** Posts one notice for the upgrades no earlier one announced. */
    fun notifyOfNewUpdates() {
        val prefs = settings.state.value
        if (!prefs.notifies) return
        val updates = store.state.value.updates
        val fresh = UpdateNotices.fresh(updates, prefs.notified)
        if (fresh.isEmpty()) return
        val (title, body) = UpdateNotices.message(fresh)
        notifier.post(title, body)
        // Replaced, not added to: an upgrade that was installed or superseded drops out, so a later release is news.
        settings.update { it.copy(notified = updates.map(UpdateNotices::key).toSet()) }
    }

    /** Checks now and then every interval, for as long as the caller runs it; a new interval starts over. */
    suspend fun run() {
        settings.state.map { it.interval }.distinctUntilChanged().collectLatest { interval ->
            while (true) {
                if (!store.state.value.isRunning) {
                    store.refresh()
                    notifyOfNewUpdates()
                }
                delay(interval)
            }
        }
    }

    private companion object {
        val REFUSED get() = tr(Res.string.notice_refused)
    }
}
