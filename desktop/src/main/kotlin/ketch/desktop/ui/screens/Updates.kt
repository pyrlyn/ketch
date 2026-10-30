package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.Confirm
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.Navigation
import org.jetbrains.compose.resources.stringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun UpdatesScreen(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    // The navigation's, not the screen's: the tray's Update all asks for this confirmation too.
    var confirming by navigation::confirmingUpdateAll
    val updates = state.updates
    Page(
        stringResource(Res.string.section_updates),
        "updates",
        buttons = {
            Text(checked(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { actions.run { refresh() } }, enabled = !state.isRunning) { Text(stringResource(Res.string.action_check_now)) }
            Button(onClick = { confirming = true }, enabled = updates.isNotEmpty() && !state.isRunning, modifier = Modifier.testTag("update-all")) {
                Text(stringResource(Res.string.action_update_all))
            }
        },
    ) {
        LazyColumn {
            if (updates.isEmpty()) item { Empty(stringResource(Res.string.updates_everything_up_to_date)) }
            items(updates, key = { "update-${it.name}" }) { upgrade ->
                PackageRow(upgrade.name, "${upgrade.installed} → ${upgrade.latest}", "update-${upgrade.name}", null) {
                    FilledTonalButton(onClick = { actions.run { upgrade(listOf(upgrade.name)) } }, enabled = !state.isRunning) { Text(stringResource(Res.string.action_update)) }
                }
            }
            if (state.held.isNotEmpty()) {
                item {
                    Text(stringResource(Res.string.updates_held_back), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = LocalSpacing.current.xl))
                    Text(
                        stringResource(Res.string.updates_held_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.held, key = { "held-${it.name}" }) { upgrade ->
                val by = upgrade.heldBy?.let { stringResource(Res.string.updates_held_by, it) } ?: stringResource(Res.string.status_pinned)
                PackageRow(upgrade.name, "${upgrade.installed} → ${upgrade.latest} · $by", "held-${upgrade.name}", null) {}
            }
        }
    }
    if (confirming) UpdateAllConfirm(updates.size, actions) { confirming = false }
}

@Composable
fun UpdateAllConfirm(
    count: Int,
    actions: Actions,
    onDone: () -> Unit,
) {
    Confirm(
        title = plural(count, Res.plurals.updates_confirm_title),
        message = stringResource(Res.string.updates_confirm_text),
        confirm = stringResource(Res.string.action_update_all),
        onConfirm = {
            onDone()
            actions.run { upgrade() }
        },
        onDismiss = onDone,
    )
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun checked(state: StoreState) = state.lastChecked?.let { tr(Res.string.updates_checked_at, CLOCK.format(it)) } ?: tr(Res.string.updates_not_checked)
