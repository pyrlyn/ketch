package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors

@Composable
fun DiscoverScreen(
    state: StoreState,
    actions: Actions,
    onOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // A new query cancels the wait for the one before; the store also shows only the newest answer.
    LaunchedEffect(query) { actions.store.searchAfterTyping(query) }
    val installed = state.installed.map { it.name }.toSet()
    val found = state.search
    val status = LocalStatusColors.current
    Page("Discover", "discover") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search the registry") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = LocalSpacing.current.md).testTag("discover-query"),
        )
        if (found.known.isEmpty() && found.repositories.isEmpty()) {
            Empty(if (query.isBlank()) "The registry lists nothing yet" else "No package matches \"${query.trim()}\"")
        } else {
            LazyColumn {
                items(found.known, key = { "known-${it.name}" }) { pkg ->
                    val summary = listOfNotNull(pkg.description, pkg.latest?.let { "Latest $it" }).joinToString(" · ")
                    PackageRow(pkg.name, summary.ifEmpty { null }, "discover-${pkg.name}", { onOpen(pkg.name) }) {
                        if (pkg.name in installed) {
                            Chip("Installed", status.installed, status.installedSubtle)
                        } else {
                            FilledTonalButton(onClick = { actions.run { install(pkg.name) } }, enabled = !state.isRunning) { Text("Install") }
                        }
                    }
                }
                if (found.repositories.isNotEmpty()) {
                    item { Text("Repositories", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = LocalSpacing.current.lg)) }
                }
                items(found.repositories, key = { "repo-${it.spec}" }) { repo ->
                    val summary = listOfNotNull(repo.description, repo.stars?.let { "★ $it" }).joinToString(" · ")
                    PackageRow(repo.spec.removePrefix("github:"), summary.ifEmpty { null }, "discover-${repo.spec}", null) {
                        FilledTonalButton(onClick = { actions.run { install(repo.spec) } }, enabled = !state.isRunning) { Text("Install") }
                    }
                }
            }
        }
    }
}
