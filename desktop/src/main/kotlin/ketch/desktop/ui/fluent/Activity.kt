// The log, newest first and grouped by day, with the running operation at the top in place of the flyout.
package ketch.desktop.ui.fluent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ketch.desktop.store.LogEntry
import ketch.desktop.store.LogLevel
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.LOG_CLOCK
import ketch.desktop.ui.LOG_ZONE
import ketch.desktop.ui.exportLog
import ketch.desktop.ui.logDays
import ketch.desktop.ui.word
import org.jetbrains.compose.resources.stringResource
import pyrlyn.fluent.BadgeKind
import pyrlyn.fluent.EmptyNote
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.LogRow
import pyrlyn.fluent.PageHead
import pyrlyn.fluent.PageTitle
import pyrlyn.fluent.Panel
import pyrlyn.fluent.SearchBox
import pyrlyn.fluent.SectionHead
import java.time.LocalDate

@Composable
fun FluentActivity(
    state: StoreState,
    actions: Actions,
) {
    val c = LocalFluent.current
    var filter by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val needle = filter.trim()
    val entries = state.log.filter { needle.isEmpty() || it.message.contains(needle, ignoreCase = true) }
    FluentPage("activity") {
        PageHead(
            title = { PageTitle(stringResource(Res.string.section_activity)) },
            actions = {
                SearchBox(filter, stringResource(Res.string.activity_filter_history), Modifier.width(FILTER_WIDTH).testTag("activity-filter")) { filter = it }
                FluentButton(stringResource(Res.string.action_export_log), enabled = state.log.isNotEmpty()) { failure = exportLog(state.log) }
            },
        )
        OperationFlyout(state) { activity -> FluentButton(stringResource(Res.string.action_cancel), enabled = !activity.isCancelling) { actions.store.cancel() } }
        failure?.let { Text(it, color = c.danger, style = LocalFluentType.current.small, modifier = Modifier.testTag("activity-message")) }
        if (entries.isEmpty()) EmptyNote(if (needle.isEmpty()) stringResource(Res.string.activity_nothing_yet) else stringResource(Res.string.activity_no_match, needle))
        for ((day, lines) in logDays(entries, LocalDate.now(LOG_ZONE))) {
            Column {
                SectionHead(day)
                DayPanel(lines)
            }
        }
    }
}

@Composable
private fun DayPanel(lines: List<LogEntry>) {
    Panel(padding = PaddingValues(horizontal = 22.dp, vertical = 4.dp), spacing = 0.dp) {
        lines.forEachIndexed { index, entry ->
            LogRow(LOG_CLOCK.format(entry.at), entry.level.word, entry.level.kind, entry.message, first = index == 0)
        }
    }
}

private val LogLevel.kind
    get() =
        when (this) {
            LogLevel.Info -> BadgeKind.Info
            LogLevel.Success -> BadgeKind.Success
            LogLevel.Warning -> BadgeKind.Warning
            LogLevel.Error -> BadgeKind.Danger
        }

private val FILTER_WIDTH = 260.dp
