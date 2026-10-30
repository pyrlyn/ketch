package ketch.desktop.ui.adwaita

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ketch.desktop.store.Activity
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.VERIFIED_NOTE
import ketch.desktop.ui.changelogNote
import ketch.desktop.ui.screens.UpdateAllConfirm
import ketch.desktop.ui.screens.checked
import ketch.desktop.ui.screens.githubRepo
import ketch.desktop.ui.screens.openConfig
import ketch.desktop.ui.tilde
import ketch.wire.Upgrade
import org.jetbrains.compose.resources.stringResource
import pyrlyn.adwaita.ActionRow
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.EmptyNote
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.OkLabel
import pyrlyn.adwaita.PageHead
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.PreferencesGroup
import pyrlyn.adwaita.ProgressBar
import pyrlyn.adwaita.RowLine
import pyrlyn.adwaita.RowMono
import pyrlyn.adwaita.RowText
import pyrlyn.adwaita.RowTitle
import pyrlyn.adwaita.Status
import pyrlyn.adwaita.StatusPill
import java.io.File

@Composable
fun AdwaitaUpdates(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val c = LocalAdwaita.current
    // The navigation's, not the screen's: the tray's Update all asks for this confirmation too.
    var confirming by navigation::confirmingUpdateAll
    var message by remember { mutableStateOf<String?>(null) }
    val updates = state.updates
    val latest = state.installed.filter { pkg -> state.outdatedVersion(pkg.name) == null && state.held.none { it.name == pkg.name } }.sortedBy { it.name }
    AdwaitaPage("updates") {
        PageHead(stringResource(Res.string.section_updates)) {
            PillButton(stringResource(Res.string.action_check_now), enabled = !state.isRunning) { actions.run { refresh() } }
            PillButton(stringResource(Res.string.action_update_all), Modifier.testTag("update-all"), PillStyle.Accent, enabled = updates.isNotEmpty() && !state.isRunning) { confirming = true }
        }
        if (updates.isEmpty()) {
            EmptyNote(stringResource(Res.string.updates_everything_up_to_date))
        } else {
            PreferencesGroup(stringResource(Res.string.updates_available), description = checked(state)) {
                BoxedList {
                    updates.forEachIndexed { index, upgrade ->
                        if (index > 0) RowLine()
                        UpdateRow(upgrade, state, actions, navigation)
                    }
                }
            }
        }
        if (state.held.isNotEmpty()) {
            PreferencesGroup(stringResource(Res.string.updates_held_back), description = stringResource(Res.string.updates_pinned_note)) {
                BoxedList {
                    state.held.forEachIndexed { index, upgrade ->
                        if (index > 0) RowLine()
                        HeldRow(upgrade) { message = it }
                    }
                }
            }
        }
        message?.let { Text(it, color = c.dangerText, style = LocalAdwaitaType.current.caption, modifier = Modifier.testTag("updates-message")) }
        if (state.lastChecked != null && latest.isNotEmpty()) {
            PreferencesGroup(stringResource(Res.string.updates_up_to_date)) {
                BoxedList {
                    latest.forEachIndexed { index, pkg ->
                        if (index > 0) RowLine()
                        ActionRow(onClick = { navigation.open = pkg.name }, leading = { PackageAvatar(pkg.name) }, trailing = { OkLabel(stringResource(Res.string.status_latest)) }) {
                            RowTitle(pkg.name)
                            RowMono(pkg.version)
                        }
                    }
                }
            }
        }
        if (updates.isNotEmpty()) Text(VERIFIED_NOTE, color = c.ink2, style = LocalAdwaitaType.current.caption)
    }
    if (confirming) UpdateAllConfirm(updates.size, actions) { confirming = false }
}

@Composable
private fun UpdateRow(
    upgrade: Upgrade,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val note = changelogNote(upgrade, actions)
    val repo = state.installed.firstOrNull { it.name == upgrade.name }?.let { githubRepo(it.source) }
    val running = state.activity?.takeIf { it.packages.containsKey(upgrade.name) }
    ActionRow(
        Modifier.testTag("update-${upgrade.name}"),
        onClick = { navigation.open = upgrade.name },
        leading = { PackageAvatar(upgrade.name) },
        trailing = {
            if (running != null) {
                PillButton(stringResource(Res.string.action_cancel), size = PillSize.Small, enabled = !running.isCancelling) { actions.store.cancel() }
            } else {
                PillButton(stringResource(Res.string.action_update), Modifier.widthIn(min = UPDATE_WIDTH), PillStyle.Update, PillSize.Small, enabled = !state.isRunning) {
                    actions.run { upgrade(listOf(upgrade.name)) }
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowTitle(upgrade.name)
            StatusPill(if (running != null) stringResource(Res.string.status_updating) else upgrade.latest, if (running != null) Status.Neutral else Status.Update)
        }
        RowMono(listOfNotNull(repo, "${upgrade.installed} → ${upgrade.latest}").joinToString(" · "))
        if (running != null) Running(running, upgrade.name) else note?.let { RowText(it) }
    }
}

@Composable
private fun Running(
    activity: Activity,
    name: String,
) {
    val progress = activity.packages.getValue(name)
    ProgressBar(progress.fraction.toFloat(), Modifier.fillMaxWidth().padding(top = 8.dp))
    activity.status?.let { RowText(it) }
}

@Composable
private fun HeldRow(
    upgrade: Upgrade,
    onMessage: (String?) -> Unit,
) {
    val by = upgrade.heldBy
    ActionRow(
        Modifier.testTag("held-${upgrade.name}"),
        leading = { PackageAvatar(upgrade.name) },
        trailing = { by?.let { PillButton(stringResource(Res.string.action_open_lock), size = PillSize.Small) { onMessage(openConfig(File(it))) } } },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowTitle(upgrade.name)
            StatusPill(stringResource(Res.string.status_pinned), Status.Neutral)
        }
        RowMono("${upgrade.installed} → ${upgrade.latest} · ${by?.let { stringResource(Res.string.updates_pinned_in, tilde(it)) } ?: stringResource(Res.string.updates_pinned_word)}")
    }
}

private val UPDATE_WIDTH = 80.dp
