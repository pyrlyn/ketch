// The Activity log as both looks show and save it.
package ketch.desktop.ui

import ketch.desktop.store.LogEntry
import ketch.desktop.store.LogLevel
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LogLevel.word
    get() =
        when (this) {
            LogLevel.Info -> "INFO"
            LogLevel.Success -> "DONE"
            LogLevel.Warning -> "WARN"
            LogLevel.Error -> "ERROR"
        }

/** [entries] newest first, by day: Today, Yesterday, then the date. */
fun logDays(
    entries: List<LogEntry>,
    today: LocalDate,
    zone: ZoneId = LOG_ZONE,
): List<Pair<String, List<LogEntry>>> =
    entries
        .asReversed()
        .groupBy { it.at.atZone(zone).toLocalDate() }
        .map { (day, lines) ->
            val title =
                when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> DAY.format(day)
                }
            title to lines
        }

/** The log as a person reads it in a text file: oldest first, one line an entry. */
fun logText(
    entries: List<LogEntry>,
    zone: ZoneId = LOG_ZONE,
) = entries.joinToString("\n", postfix = "\n") { "${STAMP.withZone(zone).format(it.at)} ${it.level.name.uppercase()} ${it.message}" }

/** Asks where and writes the log there; what to tell the person when it cannot. */
fun exportLog(entries: List<LogEntry>): String? {
    val dialog = FileDialog(null as Frame?, "Export log", FileDialog.SAVE)
    dialog.file = "ketch-activity.log"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return try {
        File(dialog.directory, name).writeText(logText(entries))
        null
    } catch (e: IOException) {
        "Could not write $name: ${e.message}"
    }
}

val LOG_ZONE: ZoneId = ZoneId.systemDefault()
val LOG_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(LOG_ZONE)
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy")
