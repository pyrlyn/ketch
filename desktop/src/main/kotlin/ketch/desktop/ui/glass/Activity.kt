// The log, newest first and grouped by day, with the running operation at the top in place of the floating bar.
package ketch.desktop.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.exportLog
import ketch.desktop.ui.logDays
import ketch.desktop.ui.searchFocus
import ketch.desktop.ui.word
import org.jetbrains.compose.resources.stringResource
import pyrlyn.glass.Badge
import pyrlyn.glass.BadgeKind
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.EmptyNote
import pyrlyn.glass.GlassButton
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.Pane
import pyrlyn.glass.SearchField
import pyrlyn.glass.SectionTitle
import pyrlyn.glass.TextSize
import pyrlyn.glass.body
import pyrlyn.glass.glass
import pyrlyn.glass.mono
import java.time.LocalDate

@Composable
fun GlassActivity(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val glass = LocalGlass.current
    var filter by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val needle = filter.trim()
    val entries = state.log.filter { needle.isEmpty() || it.message.contains(needle, ignoreCase = true) }
    val focus = searchFocus(navigation)
    GlassPage(
        stringResource(Res.string.section_activity),
        "activity",
        navigation,
        toolbar = {
            SearchField(filter, stringResource(Res.string.activity_filter_log), Modifier.testTag("activity-filter"), "⌘K", focus) { filter = it }
            GlassButton(stringResource(Res.string.action_export_log), ButtonKind.Secondary, enabled = state.log.isNotEmpty()) { failure = exportLog(state.log) }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PageContent, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { ActivityBar(state, actions) }
            failure?.let { item { Text(it, color = glass.danger, style = body(TextSize.LABEL), modifier = Modifier.testTag("activity-message")) } }
            if (entries.isEmpty()) item { EmptyNote(if (needle.isEmpty()) stringResource(Res.string.activity_nothing_yet) else stringResource(Res.string.activity_no_match, needle)) }
            for ((day, lines) in logDays(entries, LocalDate.now(LOG_ZONE))) {
                item(key = "day-$day") { SectionTitle(day, small = true) }
                item(key = "log-$day") { DayCard(lines) }
            }
        }
    }
}

@Composable
private fun DayCard(lines: List<LogEntry>) {
    val glass = LocalGlass.current
    SelectionContainer {
        Column(Modifier.fillMaxWidth().glass(Pane.Flat).padding(horizontal = 20.dp, vertical = 6.dp)) {
            lines.forEachIndexed { index, entry ->
                Row(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LOG_CLOCK.format(entry.at), color = glass.ink2, style = mono(TIME), modifier = Modifier.width(TIME_WIDTH))
                    Box(Modifier.width(LEVEL_WIDTH), contentAlignment = Alignment.Center) { Level(entry.level) }
                    Text(entry.message, color = glass.ink, style = body(TextSize.LABEL))
                }
                if (index < lines.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(glass.separator))
            }
        }
    }
}

@Composable
private fun Level(level: LogLevel) {
    val kind =
        when (level) {
            LogLevel.Info -> BadgeKind.Neutral
            LogLevel.Success -> BadgeKind.Success
            LogLevel.Warning -> BadgeKind.Warning
            LogLevel.Error -> BadgeKind.Danger
        }
    Badge(level.word, kind)
}

private const val TIME = 12
private val TIME_WIDTH = 56.dp
private val LEVEL_WIDTH = 64.dp
