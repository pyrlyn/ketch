package ketch.desktop.ui.adwaita

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import ketch.desktop.ui.tilde
import ketch.desktop.ui.whereTo
import ketch.wire.Package
import ketch.wire.Settings
import org.jetbrains.compose.resources.stringResource
import pyrlyn.adwaita.ActionRow
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.EmptyNote
import pyrlyn.adwaita.Entry
import pyrlyn.adwaita.FlatButton
import pyrlyn.adwaita.PageHead
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.PreferencesGroup
import pyrlyn.adwaita.RowLine
import pyrlyn.adwaita.RowMono
import pyrlyn.adwaita.RowTitle
import pyrlyn.adwaita.Status
import pyrlyn.adwaita.StatusPill
import pyrlyn.glass.Glyph

@Composable
fun AdwaitaInstalled(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    config: Settings?,
) {
    var filter by remember { mutableStateOf("") }
    var uninstalling by remember { mutableStateOf<Package?>(null) }
    val needle = filter.trim()
    val rows = state.installed.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }.sortedBy { it.name }
    AdwaitaPage("installed") {
        PageHead(stringResource(Res.string.section_installed)) { Entry(filter, stringResource(Res.string.installed_filter), Modifier.width(FILTER_WIDTH).testTag("installed-filter"), Glyph.Search) { filter = it } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(plural(state.installed.size, Res.plurals.packages), Status.Neutral)
            config?.let { StatusPill(tilde(it.root), Status.Neutral) }
            StatusPill("${plural(state.updates.size, Res.plurals.updates)} · ${plural(state.held.size, Res.plurals.pinned_count)}", Status.Neutral)
        }
        when {
            rows.isNotEmpty() -> {
                PreferencesGroup(stringResource(Res.string.installed_packages)) {
                    BoxedList {
                        rows.forEachIndexed { index, pkg ->
                            if (index > 0) RowLine()
                            InstalledRow(pkg, state, actions, navigation) {
                                navigation.selected = pkg.name
                                uninstalling = pkg
                            }
                        }
                    }
                }
            }

            needle.isEmpty() -> {
                EmptyNote(stringResource(Res.string.installed_none_explore))
            }

            else -> {
                EmptyNote(stringResource(Res.string.installed_no_match, needle))
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
    ActionRow(
        Modifier.testTag("installed-${pkg.name}"),
        selected = navigation.selected == pkg.name,
        onClick = { navigation.open = pkg.name },
        leading = { PackageAvatar(pkg.name) },
        trailing = {
            if (update != null) PillButton(stringResource(Res.string.action_update), style = PillStyle.Update, size = PillSize.Small, enabled = !state.isRunning) { actions.run { upgrade(listOf(pkg.name)) } }
            FolderButton(pkg.binaries.firstOrNull() ?: pkg.prefix)
            PillButton(stringResource(Res.string.action_uninstall_ellipsis), style = PillStyle.FlatDanger, size = PillSize.Small, enabled = !state.isRunning, onClick = onUninstall)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowTitle(pkg.name)
            when {
                pinned -> StatusPill(stringResource(Res.string.status_pinned), Status.Neutral)
                update != null -> StatusPill(stringResource(Res.string.package_update_available, update), Status.Update)
                else -> StatusPill(stringResource(Res.string.status_installed), Status.Ok)
            }
        }
        RowMono(listOfNotNull(githubRepo(pkg.source) ?: pkg.source, pkg.version, whereTo(pkg, state)).joinToString(" · "))
    }
}

@Composable
fun FolderButton(path: String) {
    FlatButton(Glyph.Folder, stringResource(Res.string.action_open_folder), enabled = pathExists(path)) { reveal(path) }
}

private val FILTER_WIDTH = 260.dp
