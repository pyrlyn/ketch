package ketch.desktop.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test
    fun aFirstRunHasTheDefaults() = assertEquals(Prefs(), AppSettings(MemoryKeyValues()).state.value)

    @Test
    fun whatIsSavedIsReadBack() {
        val values = MemoryKeyValues()
        val saved = Prefs(intervalMinutes = 360, prereleases = true, notifies = true, notified = setOf("rg@2", "fd@1"))
        AppSettings(values).update { saved }
        assertEquals(saved, AppSettings(values).state.value)
    }

    @Test
    fun unreadableValuesFallBackToTheDefaults() {
        val values = MemoryKeyValues()
        values.put("updateCheckIntervalMinutes", "7")
        values.put("includePrereleases", "yes")
        values.put("notifiesOfUpdates", "")
        assertEquals(Prefs(), AppSettings(values).state.value)
    }

    @Test
    fun anIntervalNotOfferedIsRefused() {
        val settings = AppSettings(MemoryKeyValues())
        settings.update { it.copy(intervalMinutes = 1, prereleases = true) }
        assertEquals(Prefs(prereleases = true), settings.state.value)
    }
}
