package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import ketch.wire.Package

@Composable
fun InstalledScreen(
    state: StoreState,
    actions: Actions,
    onOpen: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var uninstalling by remember { mutableStateOf<Package?>(null) }
    val needle = filter.trim()
    val rows = state.installed.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }.sortedBy { it.name }
    val status = LocalStatusColors.current
    Page("Installed", "installed") {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter installed packages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = LocalSpacing.current.md).testTag("installed-filter"),
        )
        if (rows.isEmpty()) {
            if (needle.isEmpty()) Empty("Nothing is installed yet", "Find something in Discover.") else Empty("No installed package matches \"$needle\"")
        } else {
            LazyColumn {
                items(rows, key = { it.name }) { pkg ->
                    val update = state.outdatedVersion(pkg.name)
                    val held = state.held.any { it.name == pkg.name }
                    PackageRow(pkg.name, "${pkg.version} · ${githubRepo(pkg.source) ?: pkg.source}", "installed-${pkg.name}", { onOpen(pkg.name) }) {
                        when {
                            update != null -> Chip("Update to $update", status.update, status.updateSubtle)
                            held -> Chip("Held", status.warning, status.warningSubtle)
                        }
                        TextButton(onClick = { uninstalling = pkg }, enabled = !state.isRunning) { Text("Uninstall") }
                    }
                }
            }
        }
    }
    uninstalling?.let { pkg -> UninstallConfirm(pkg, actions) { uninstalling = null } }
}
