package ketch.desktop.ui.fluent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import ketch.desktop.ui.tilde
import ketch.desktop.ui.whereTo
import ketch.wire.Package
import ketch.wire.Settings
import org.jetbrains.compose.resources.stringResource
import pyrlyn.fluent.AppIconSize
import pyrlyn.fluent.BadgeKind
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.Corners
import pyrlyn.fluent.EmptyNote
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.ListRow
import pyrlyn.fluent.PageHead
import pyrlyn.fluent.PageTitle
import pyrlyn.fluent.Pill
import pyrlyn.fluent.RowGroup
import pyrlyn.fluent.RowMeta
import pyrlyn.fluent.RowTitle
import pyrlyn.fluent.SearchBox
import pyrlyn.fluent.StatusBadge

@Composable
fun FluentInstalled(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    config: Settings?,
) {
    var filter by remember { mutableStateOf("") }
    var uninstalling by remember { mutableStateOf<Package?>(null) }
    val needle = filter.trim()
    val rows = state.installed.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }.sortedBy { it.name }
    FluentPage("installed") {
        PageHead(
            title = { PageTitle(stringResource(Res.string.section_installed)) },
            actions = { SearchBox(filter, stringResource(Res.string.installed_filter), Modifier.width(FILTER_WIDTH).testTag("installed-filter")) { filter = it } },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(plural(state.installed.size, Res.plurals.packages))
            config?.let { Pill(tilde(it.root), code = true) }
            Pill("${plural(state.updates.size, Res.plurals.updates)} · ${plural(state.held.size, Res.plurals.pinned_count)}")
        }
        if (rows.isEmpty()) {
            if (needle.isEmpty()) EmptyNote(stringResource(Res.string.installed_none), stringResource(Res.string.installed_find_something)) else EmptyNote(stringResource(Res.string.installed_no_match, needle))
        }
        RowGroup {
            for (pkg in rows) {
                InstalledRow(pkg, state, actions, navigation) {
                    navigation.selected = pkg.name
                    uninstalling = pkg
                }
            }
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
    val pinned = state.held.any { it.name == pkg.name }
    ListRow(
        Modifier.testTag("installed-${pkg.name}").clip(Corners.Item).clickable { navigation.open = pkg.name },
        selected = navigation.selected == pkg.name,
        leading = { PackageIcon(pkg.name, AppIconSize.Small, raised = it) },
        actions = {
            if (update != null) FluentButton(stringResource(Res.string.action_upgrade), ButtonStyle.Update, enabled = !state.isRunning) { actions.run { upgrade(listOf(pkg.name)) } }
            RevealButton(pkg.binaries.firstOrNull() ?: pkg.prefix)
            FluentButton(stringResource(Res.string.action_uninstall_ellipsis), ButtonStyle.Danger, enabled = !state.isRunning, onClick = onUninstall)
        },
    ) {
        RowTitle(pkg.name) {
            when {
                pinned -> StatusBadge(stringResource(Res.string.status_pinned), BadgeKind.Neutral)
                update != null -> StatusBadge(stringResource(Res.string.package_update_available, update), BadgeKind.Update)
                else -> StatusBadge(stringResource(Res.string.status_installed), BadgeKind.Success)
            }
        }
        RowMeta(listOfNotNull(githubRepo(pkg.source) ?: pkg.source, pkg.version, whereTo(pkg, state)).joinToString(" · "))
    }
}

@Composable
fun RevealButton(path: String) {
    FluentButton(stringResource(Res.string.action_show_explorer), enabled = pathExists(path)) { reveal(path) }
}

private val FILTER_WIDTH = 260.dp
