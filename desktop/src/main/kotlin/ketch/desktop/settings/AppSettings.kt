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
    /** The macOS and Windows looks', in the Swift app's stored forms: a preset's name or `#rrggbb`; what nothing can read is the standard. */
    val tint: String = STANDARD_TINT,
    val glass: String = REGULAR_GLASS,
    val accent: String = STANDARD_ACCENT,
    /** The Windows look's window material. */
    val material: String = ACRYLIC,
    /** The opacity of the backdrop wash, 0..[MAX_WASH]. */
    val wash: Double = STANDARD_WASH,
) {
    val interval: Duration get() = intervalMinutes.minutes

    /** These settings with the looks' own back at the standard. */
    fun standardLook() = copy(tint = STANDARD_TINT, glass = REGULAR_GLASS, accent = STANDARD_ACCENT, material = ACRYLIC, wash = STANDARD_WASH)

    companion object {
        const val STANDARD_TINT = "clear"
        const val REGULAR_GLASS = "regular"
        const val CLEAR_GLASS = "clear"
        const val STANDARD_ACCENT = "system"
        const val MICA = "mica"
        const val ACRYLIC = "acrylic"
        const val TRANSPARENT = "transparent"

        // tokens.json's `opacity.wash` and `opacity.washMax`, which the contrast check holds up to and no further.
        const val STANDARD_WASH = 0.14
        const val MAX_WASH = 0.2

        fun clampWash(wash: Double) = if (wash.isFinite()) wash.coerceIn(0.0, MAX_WASH) else STANDARD_WASH
    }
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
            val next = change(old).let { it.copy(wash = Prefs.clampWash(it.wash)) }
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
            tint = values.get(TINT) ?: defaults.tint,
            glass = values.get(GLASS) ?: defaults.glass,
            accent = values.get(ACCENT) ?: defaults.accent,
            material = values.get(MATERIAL) ?: defaults.material,
            wash = Prefs.clampWash(values.get(WASH)?.toDoubleOrNull() ?: defaults.wash),
        )
    }

    private fun save(prefs: Prefs) {
        values.put(INTERVAL, prefs.intervalMinutes.toString())
        values.put(PRERELEASES, prefs.prereleases.toString())
        values.put(NOTIFIES, prefs.notifies.toString())
        values.put(NOTIFIED, prefs.notified.sorted().joinToString("\n"))
        values.put(TINT, prefs.tint)
        values.put(GLASS, prefs.glass)
        values.put(ACCENT, prefs.accent)
        values.put(MATERIAL, prefs.material)
        values.put(WASH, prefs.wash.toString())
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
        private const val TINT = "appearanceTint"
        private const val GLASS = "appearanceGlassStyle"
        private const val ACCENT = "appearanceAccent"
        private const val WASH = "appearanceWash"
        private const val MATERIAL = "appearanceMaterial"
    }
}
