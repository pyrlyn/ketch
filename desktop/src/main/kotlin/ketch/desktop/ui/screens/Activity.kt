package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ketch.desktop.store.LogLevel
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.LocalMonoStyle
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import org.jetbrains.compose.resources.stringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ActivityScreen(state: StoreState) {
    val log = state.log
    val list = rememberLazyListState()
    // The newest line is the one to read; follow it as it comes.
    LaunchedEffect(log.lastOrNull()?.id) { if (log.isNotEmpty()) list.animateScrollToItem(log.lastIndex) }
    val status = LocalStatusColors.current
    Page(stringResource(Res.string.section_activity), "activity") {
        if (log.isEmpty()) {
            Empty(stringResource(Res.string.activity_nothing_yet))
        } else {
            SelectionContainer {
                LazyColumn(state = list) {
                    items(log, key = { it.id }) { entry ->
                        val (mark, ink) =
                            when (entry.level) {
                                LogLevel.Info -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                                LogLevel.Success -> "✓" to status.installed
                                LogLevel.Warning -> "!" to status.warning
                                LogLevel.Error -> "✕" to status.error
                            }
                        Row(Modifier.padding(vertical = LocalSpacing.current.xxs), horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                            Text(CLOCK.format(entry.at), style = LocalMonoStyle.current, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(mark, color = ink, style = LocalMonoStyle.current)
                            Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = if (entry.level == LogLevel.Info) Color.Unspecified else ink)
                        }
                    }
                }
            }
        }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
