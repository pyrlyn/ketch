package ketch.desktop.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.pathExists
import ketch.desktop.ui.reveal
import ketch.desktop.ui.screens.UninstallConfirm
import ketch.desktop.ui.screens.githubRepo
import ketch.desktop.ui.searchFocus
import ketch.desktop.ui.tilde
import ketch.desktop.ui.whereTo
import ketch.wire.Package
import ketch.wire.Settings
import org.jetbrains.compose.resources.stringResource
import pyrlyn.glass.Badge
import pyrlyn.glass.BadgeKind
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.EmptyNote
import pyrlyn.glass.FactChip
import pyrlyn.glass.GlassButton
import pyrlyn.glass.IconSize
import pyrlyn.glass.LetterIcon
import pyrlyn.glass.ListRow
import pyrlyn.glass.SearchField

@Composable
fun GlassInstalled(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    config: Settings?,
) {
    var filter by remember { mutableStateOf("") }
    var uninstalling by remember { mutableStateOf<Package?>(null) }
    val needle = filter.trim()
    val rows = state.installed.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }.sortedBy { it.name }
    val focus = searchFocus(navigation)
    GlassPage(
        stringResource(Res.string.section_installed),
        "installed",
        navigation,
        toolbar = { SearchField(filter, stringResource(Res.string.installed_filter_packages), Modifier.testTag("installed-filter"), "⌘K", focus) { filter = it } },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PageContent, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactChip(plural(state.installed.size, Res.plurals.packages))
                    config?.let { FactChip(tilde(it.root)) }
                    FactChip("${plural(state.updates.size, Res.plurals.updates)} · ${plural(state.held.size, Res.plurals.pinned_count)}")
                }
            }
            if (rows.isEmpty()) {
                item {
                    if (needle.isEmpty()) EmptyNote(stringResource(Res.string.installed_none), stringResource(Res.string.installed_find_something)) else EmptyNote(stringResource(Res.string.installed_no_match, needle))
                }
            }
            items(rows, key = { it.name }) { pkg -> InstalledRow(pkg, state, actions, navigation) { uninstalling = pkg } }
        }
    }
    uninstalling?.let { pkg -> UninstallConfirm(pkg, actions) { uninstalling = null } }
}

@Composable
private fun InstalledRow(
    pkg: Package,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    onUninstall: () -> Unit,
) {
    val update = state.outdatedVersion(pkg.name)
    val held = state.held.firstOrNull { it.name == pkg.name }
    ListRow(
        pkg.name,
        listOfNotNull(githubRepo(pkg.source) ?: pkg.source, pkg.version, whereTo(pkg, state)).joinToString(" · "),
        Modifier.testTag("installed-${pkg.name}"),
        onClick = { navigation.open = pkg.name },
        icon = { LetterIcon(pkg.name, IconSize.Small) },
        badge = {
            when {
                held != null -> Badge(stringResource(Res.string.status_pinned), BadgeKind.Neutral)
                update != null -> Badge(stringResource(Res.string.action_update_to, update), BadgeKind.Attention)
                else -> Badge(stringResource(Res.string.status_installed), BadgeKind.Success)
            }
        },
    ) {
        RevealButton(pkg.binaries.firstOrNull() ?: pkg.prefix)
        GlassButton(stringResource(Res.string.action_uninstall_ellipsis), ButtonKind.Danger, enabled = !state.isRunning, onClick = onUninstall)
        if (update != null) GlassButton(stringResource(Res.string.action_upgrade), ButtonKind.Attention, enabled = !state.isRunning) { actions.run { upgrade(listOf(pkg.name)) } }
    }
}

@Composable
fun RevealButton(path: String) {
    GlassButton(stringResource(Res.string.action_reveal_finder), ButtonKind.Secondary, enabled = pathExists(path)) { reveal(path) }
}
