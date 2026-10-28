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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.Confirm
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.plural
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun UpdatesScreen(
    state: StoreState,
    actions: Actions,
) {
    var confirming by remember { mutableStateOf(false) }
    val updates = state.updates
    Page(
        "Updates",
        "updates",
        buttons = {
            Text(checked(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { actions.run { refresh() } }, enabled = !state.isRunning) { Text("Check now") }
            Button(onClick = { confirming = true }, enabled = updates.isNotEmpty() && !state.isRunning, modifier = Modifier.testTag("update-all")) {
                Text("Update all")
            }
        },
    ) {
        LazyColumn {
            if (updates.isEmpty()) item { Empty("Everything is up to date") }
            items(updates, key = { "update-${it.name}" }) { upgrade ->
                PackageRow(upgrade.name, "${upgrade.installed} → ${upgrade.latest}", "update-${upgrade.name}", null) {
                    FilledTonalButton(onClick = { actions.run { upgrade(listOf(upgrade.name)) } }, enabled = !state.isRunning) { Text("Update") }
                }
            }
            if (state.held.isNotEmpty()) {
                item {
                    Text("Held back", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = LocalSpacing.current.xl))
                    Text(
                        "A pin or a project's ketch.lock holds these, so Update all leaves them where they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.held, key = { "held-${it.name}" }) { upgrade ->
                val by = upgrade.heldBy?.let { "Held by $it" } ?: "Pinned"
                PackageRow(upgrade.name, "${upgrade.installed} → ${upgrade.latest} · $by", "held-${upgrade.name}", null) {}
            }
        }
    }
    if (confirming) {
        Confirm(
            title = "Update ${plural(updates.size, "package")}?",
            message = "Each package is downloaded, verified and linked in turn. Held packages stay where they are.",
            confirm = "Update all",
            onConfirm = {
                confirming = false
                actions.run { upgrade() }
            },
            onDismiss = { confirming = false },
        )
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun checked(state: StoreState) = state.lastChecked?.let { "Checked at ${CLOCK.format(it)}" } ?: "Not checked yet"
