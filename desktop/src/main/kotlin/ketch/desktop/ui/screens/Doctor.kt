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
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.LocalMonoStyle
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import ketch.wire.CheckOutcome
import org.jetbrains.compose.resources.stringResource

@Composable
fun DoctorScreen(
    state: StoreState,
    actions: Actions,
) {
    val status = LocalStatusColors.current
    Page(stringResource(Res.string.section_doctor), "doctor", buttons = { Button(onClick = { actions.run { runDoctor() } }) { Text(stringResource(Res.string.action_run_checks)) } }) {
        val summary =
            when {
                state.checks.isEmpty() -> stringResource(Res.string.doctor_no_checks)
                state.problemCount == 0 -> stringResource(Res.string.doctor_no_problems)
                else -> plural(state.problemCount, Res.plurals.problems_found)
            }
        Text(summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = LocalSpacing.current.md).testTag("doctor-summary"))
        LazyColumn {
            items(state.checks, key = { it.name }) { check ->
                val (word, ink) =
                    when (check.outcome) {
                        CheckOutcome.Ok -> stringResource(Res.string.doctor_ok_word) to status.installed
                        CheckOutcome.Warn -> stringResource(Res.string.doctor_warning_word) to status.warning
                        CheckOutcome.Fail -> stringResource(Res.string.doctor_problem_word) to status.error
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
