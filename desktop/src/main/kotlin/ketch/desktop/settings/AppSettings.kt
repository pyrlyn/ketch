// The app's own preferences. Anything ketch itself is configured by stays in the CLI's config.toml: the app never
// stores tokens or a second copy of ketch's settings.
package ketch.desktop.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class Prefs(
    /** Minutes between background update checks, one of [AppSettings.INTERVALS]. */
    val intervalMinutes: Int = AppSettings.DEFAULT_INTERVAL,
    val prereleases: Boolean = false,
    /** Off until the person turns it on, which is also when the system is asked. */
    val notifies: Boolean = false,
    /** `UpdateNotices.key`s already announced, so a check that finds the same upgrades stays quiet. */
    val notified: Set<String> = emptySet(),
) {
    val interval: Duration get() = intervalMinutes.minutes
}

/** Where the preferences live: `java.util.prefs` in the app, a map in tests. */
interface KeyValues {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )
}

class MemoryKeyValues : KeyValues {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String) = synchronized(values) { values[key] }

    override fun put(
        key: String,
        value: String,
    ) = synchronized(values) { values[key] = value }
}

/** The user's own preference node; a store the system cannot write to is warned about once and skipped. */
class SystemKeyValues(
    private val node: Preferences = Preferences.userRoot().node("io/github/pyrlyn/ketch"),
) : KeyValues {
    override fun get(key: String): String? = node.get(key, null)

    override fun put(
        key: String,
        value: String,
    ) {
        node.put(key, value)
        try {
            node.flush()
        } catch (e: BackingStoreException) {
            System.err.println("ketch: could not save the app's settings: ${e.message}")
        }
    }
}

class AppSettings(
    private val values: KeyValues,
) {
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<Prefs> = mutable.asStateFlow()

    fun update(change: (Prefs) -> Prefs) {
        mutable.update { old ->
            val next = change(old)
            if (next.intervalMinutes in INTERVALS) next else next.copy(intervalMinutes = old.intervalMinutes)
        }
        save(mutable.value)
    }

    private fun load(): Prefs {
        val defaults = Prefs()
        return Prefs(
            intervalMinutes = values.get(INTERVAL)?.toIntOrNull()?.takeIf { it in INTERVALS } ?: defaults.intervalMinutes,
            prereleases = values.get(PRERELEASES)?.toBooleanStrictOrNull() ?: defaults.prereleases,
            notifies = values.get(NOTIFIES)?.toBooleanStrictOrNull() ?: defaults.notifies,
            notified =
                values
                    .get(NOTIFIED)
                    ?.split('\n')
                    ?.filter { it.isNotEmpty() }
                    ?.toSet() ?: defaults.notified,
        )
    }

    private fun save(prefs: Prefs) {
        values.put(INTERVAL, prefs.intervalMinutes.toString())
        values.put(PRERELEASES, prefs.prereleases.toString())
        values.put(NOTIFIES, prefs.notifies.toString())
        values.put(NOTIFIED, prefs.notified.sorted().joinToString("\n"))
    }

    companion object {
        /** The choices offered for the update check, in minutes. */
        val INTERVALS = listOf(15, 60, 360, 1440)
        const val DEFAULT_INTERVAL = 60

        // The macOS app's UserDefaults keys, so the two read alike.
        private const val INTERVAL = "updateCheckIntervalMinutes"
        private const val PRERELEASES = "includePrereleases"
        private const val NOTIFIES = "notifiesOfUpdates"
        private const val NOTIFIED = "notifiedUpgrades"
    }
}
