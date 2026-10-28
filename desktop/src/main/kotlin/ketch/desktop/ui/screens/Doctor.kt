package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.LocalMonoStyle
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import ketch.desktop.ui.plural
import ketch.wire.CheckOutcome

@Composable
fun DoctorScreen(
    state: StoreState,
    actions: Actions,
) {
    val status = LocalStatusColors.current
    Page("Doctor", "doctor", buttons = { Button(onClick = { actions.run { runDoctor() } }) { Text("Run checks") } }) {
        val summary =
            when {
                state.checks.isEmpty() -> "No checks have run yet"
                state.problemCount == 0 -> "No problems found"
                else -> plural(state.problemCount, "problem") + " found"
            }
        Text(summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = LocalSpacing.current.md).testTag("doctor-summary"))
        LazyColumn {
            items(state.checks, key = { it.name }) { check ->
                val (word, ink) =
                    when (check.outcome) {
                        CheckOutcome.Ok -> "ok" to status.installed
                        CheckOutcome.Warn -> "warning" to status.warning
                        CheckOutcome.Fail -> "problem" to status.error
                    }
                Row(Modifier.padding(vertical = LocalSpacing.current.sm), horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
                    Text(word, color = ink, style = MaterialTheme.typography.labelMedium)
                    Column {
                        Text(check.name, style = MaterialTheme.typography.titleSmall)
                        Text(check.detail, style = MaterialTheme.typography.bodyMedium)
                        check.fix?.let { fix -> SelectionContainer { Text(fix, style = LocalMonoStyle.current, color = MaterialTheme.colorScheme.primary) } }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
