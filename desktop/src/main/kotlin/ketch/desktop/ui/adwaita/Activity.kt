package ketch.desktop.ui.adwaita

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import ketch.desktop.store.Activity
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
import pyrlyn.adwaita.ActionRow
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.EmptyNote
import pyrlyn.adwaita.Entry
import pyrlyn.adwaita.IconGradient
import pyrlyn.adwaita.IconSphere
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.PageHead
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PreferencesGroup
import pyrlyn.adwaita.ProgressBar
import pyrlyn.adwaita.RowLine
import pyrlyn.adwaita.RowText
import pyrlyn.adwaita.RowTitle
import pyrlyn.adwaita.Spinner
import pyrlyn.glass.Glyph
import java.time.LocalDate

@Composable
fun AdwaitaActivity(
    state: StoreState,
    actions: Actions,
) {
    val c = LocalAdwaita.current
    var filter by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val needle = filter.trim()
    val entries = state.log.filter { needle.isEmpty() || it.message.contains(needle, ignoreCase = true) }
    AdwaitaPage("activity") {
        PageHead(stringResource(Res.string.section_activity)) {
            Entry(filter, stringResource(Res.string.activity_filter_history), Modifier.width(FILTER_WIDTH).testTag("activity-filter"), Glyph.Search) { filter = it }
            PillButton(stringResource(Res.string.action_export_log), enabled = state.log.isNotEmpty()) { failure = exportLog(state.log) }
        }
        state.activity?.let { Operation(it, actions) }
        failure?.let { Text(it, color = c.dangerText, style = LocalAdwaitaType.current.caption, modifier = Modifier.testTag("activity-message")) }
        if (entries.isEmpty()) EmptyNote(if (needle.isEmpty()) stringResource(Res.string.activity_nothing_yet) else stringResource(Res.string.activity_no_match, needle))
        for ((day, lines) in logDays(entries, LocalDate.now(LOG_ZONE))) {
            PreferencesGroup(day) {
                BoxedList {
                    lines.forEachIndexed { index, entry ->
                        if (index > 0) RowLine()
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

/** The running operation: here it has room for its progress, so no toast stands in for it. */
@Composable
private fun Operation(
    activity: Activity,
    actions: Actions,
) {
    BoxedList(Modifier.testTag("activity-bar")) {
        ActionRow(
            leading = { Spinner(LocalAdwaita.current.accent) },
            trailing = { PillButton(stringResource(Res.string.action_cancel), size = PillSize.Small, enabled = !activity.isCancelling) { actions.store.cancel() } },
        ) {
            RowTitle(activity.title)
            activity.status?.let { RowText(it) }
            ProgressBar(activity.fraction.toFloat(), Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val c = LocalAdwaita.current
    ActionRow(
        leading = { IconSphere(entry.level.glyph, entry.level.gradient, small = true) },
        trailing = { Text(LOG_CLOCK.format(entry.at), color = c.ink2, style = LocalAdwaitaType.current.caption) },
    ) {
        RowTitle(entry.message)
        RowText(entry.level.word)
    }
}

private val LogLevel.glyph
    get() =
        when (this) {
            LogLevel.Info -> Glyph.Info
            LogLevel.Success -> Glyph.Check
            LogLevel.Warning -> Glyph.Warning
            LogLevel.Error -> Glyph.Cross
        }

private val LogLevel.gradient
    get() =
        when (this) {
            LogLevel.Info -> IconGradient.Blue
            LogLevel.Success -> IconGradient.Mint
            LogLevel.Warning -> IconGradient.Orange
            LogLevel.Error -> IconGradient.Coral
        }

private val FILTER_WIDTH = 240.dp
