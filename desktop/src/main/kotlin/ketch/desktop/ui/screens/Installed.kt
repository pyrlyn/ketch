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
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import ketch.wire.Package
import org.jetbrains.compose.resources.stringResource

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
    Page(stringResource(Res.string.section_installed), "installed") {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text(stringResource(Res.string.installed_filter_packages)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = LocalSpacing.current.md).testTag("installed-filter"),
        )
        if (rows.isEmpty()) {
            if (needle.isEmpty()) Empty(stringResource(Res.string.installed_none), stringResource(Res.string.installed_find_something)) else Empty(stringResource(Res.string.installed_no_match, needle))
        } else {
            LazyColumn {
                items(rows, key = { it.name }) { pkg ->
                    val update = state.outdatedVersion(pkg.name)
                    val held = state.held.any { it.name == pkg.name }
                    PackageRow(pkg.name, "${pkg.version} · ${githubRepo(pkg.source) ?: pkg.source}", "installed-${pkg.name}", { onOpen(pkg.name) }) {
                        when {
                            update != null -> Chip(stringResource(Res.string.action_update_to, update), status.update, status.updateSubtle)
                            held -> Chip(stringResource(Res.string.status_held), status.warning, status.warningSubtle)
                        }
                        TextButton(onClick = { uninstalling = pkg }, enabled = !state.isRunning) { Text(stringResource(Res.string.action_uninstall)) }
                    }
                }
            }
        }
    }
    uninstalling?.let { pkg -> UninstallConfirm(pkg, actions) { uninstalling = null } }
}
