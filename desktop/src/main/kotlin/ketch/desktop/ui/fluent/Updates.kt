package ketch.desktop.ui.fluent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import pyrlyn.fluent.AppIconSize
import pyrlyn.fluent.BadgeKind
import pyrlyn.fluent.BusyButton
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.EmptyNote
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.InfoBar
import pyrlyn.fluent.LiftPanel
import pyrlyn.fluent.ListRow
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.PageHead
import pyrlyn.fluent.PageTitle
import pyrlyn.fluent.ReleaseTag
import pyrlyn.fluent.RowGroup
import pyrlyn.fluent.RowMeta
import pyrlyn.fluent.RowTitle
import pyrlyn.fluent.SectionHead
import pyrlyn.fluent.StatusBadge
import java.io.File

@Composable
fun FluentUpdates(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val c = LocalFluent.current
    val type = LocalFluentType.current
    // The navigation's, not the screen's: the tray's Update all asks for this confirmation too.
    var confirming by navigation::confirmingUpdateAll
    var message by remember { mutableStateOf<String?>(null) }
    val updates = state.updates
    FluentPage("updates") {
        PageHead(
            title = { PageTitle(stringResource(Res.string.section_updates)) },
            actions = {
                Text(checked(state), color = c.ink2, style = type.small)
                FluentButton(stringResource(Res.string.action_check_now), enabled = !state.isRunning) { actions.run { refresh() } }
                FluentButton(stringResource(Res.string.action_update_all), ButtonStyle.Accent, Modifier.testTag("update-all"), enabled = updates.isNotEmpty() && !state.isRunning) { confirming = true }
            },
        )
        if (updates.isEmpty()) EmptyNote(stringResource(Res.string.updates_everything_up_to_date))
        for (upgrade in updates) UpdateCard(upgrade, state, actions, navigation)
        if (state.held.isNotEmpty()) {
            Column {
                SectionHead(stringResource(Res.string.updates_pinned_by_lock))
                RowGroup { for (upgrade in state.held) HeldRow(upgrade) { message = it } }
            }
        }
        message?.let { Text(it, color = c.danger, style = type.small, modifier = Modifier.testTag("updates-message")) }
        if (updates.isNotEmpty()) InfoBar(stringResource(Res.string.updates_verified), VERIFIED_NOTE)
    }
    if (confirming) UpdateAllConfirm(updates.size, actions) { confirming = false }
}

@Composable
private fun UpdateCard(
    upgrade: Upgrade,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val c = LocalFluent.current
    val type = LocalFluentType.current
    val note = changelogNote(upgrade, actions)
    val repo = state.installed.firstOrNull { it.name == upgrade.name }?.let { githubRepo(it.source) }
    LiftPanel(Modifier.fillMaxWidth().testTag("update-${upgrade.name}")) { hovered ->
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(upgrade.name, AppIconSize.Large, raised = hovered)
            Column(Modifier.weight(1f).widthIn(min = BODY_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(upgrade.name, color = c.ink, style = type.subtitle)
                    ReleaseTag(upgrade.latest)
                }
                Text(listOfNotNull(repo, "${upgrade.installed} → ${upgrade.latest}").joinToString(" · "), color = c.ink2, style = type.mono)
                note?.let { Text(it, color = c.ink2, style = type.body, maxLines = 2) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FluentButton(stringResource(Res.string.action_changelog), ButtonStyle.Subtle) { navigation.open = upgrade.name }
                if (state.activity?.packages?.containsKey(upgrade.name) == true) {
                    BusyButton(stringResource(Res.string.action_updating))
                } else {
                    FluentButton(stringResource(Res.string.action_update), ButtonStyle.Update, Modifier.widthIn(min = UPDATE_WIDTH), enabled = !state.isRunning) { actions.run { upgrade(listOf(upgrade.name)) } }
                }
            }
        }
    }
}

@Composable
private fun HeldRow(
    upgrade: Upgrade,
    onMessage: (String?) -> Unit,
) {
    val by = upgrade.heldBy
    ListRow(
        Modifier.testTag("held-${upgrade.name}"),
        leading = { PackageIcon(upgrade.name, AppIconSize.Small, raised = it) },
        actions = { by?.let { FluentButton(stringResource(Res.string.action_open_lock)) { onMessage(openConfig(File(it))) } } },
    ) {
        RowTitle(upgrade.name) { StatusBadge(stringResource(Res.string.status_pinned), BadgeKind.Neutral) }
        RowMeta("${upgrade.name} ${upgrade.installed} → ${upgrade.latest} · ${by?.let { stringResource(Res.string.updates_pinned_in, tilde(it)) } ?: stringResource(Res.string.updates_pinned_word)}")
    }
}

private val BODY_WIDTH = 320.dp
private val UPDATE_WIDTH = 96.dp
