package ketch.desktop.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.VERIFIED_NOTE
import ketch.desktop.ui.changelogNote
import ketch.desktop.ui.screens.UpdateAllConfirm
import ketch.desktop.ui.screens.checked
import ketch.desktop.ui.screens.githubRepo
import ketch.desktop.ui.tilde
import ketch.wire.Upgrade
import pyrlyn.glass.Badge
import pyrlyn.glass.BadgeKind
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.EmptyNote
import pyrlyn.glass.GlassButton
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon
import pyrlyn.glass.IconSize
import pyrlyn.glass.LetterIcon
import pyrlyn.glass.ListRow
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.Pane
import pyrlyn.glass.SectionTitle
import pyrlyn.glass.Tag
import pyrlyn.glass.TextSize
import pyrlyn.glass.body
import pyrlyn.glass.display
import pyrlyn.glass.glass
import pyrlyn.glass.mono
import pyrlyn.glass.tilt

@Composable
fun GlassUpdates(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val glass = LocalGlass.current
    // The navigation's, not the screen's: the tray's Update all asks for this confirmation too.
    var confirming by navigation::confirmingUpdateAll
    val updates = state.updates
    GlassPage(
        "Updates",
        "updates",
        navigation,
        toolbar = {
            Text(checked(state), color = glass.ink2, style = body(TextSize.LABEL))
            GlassButton("Check now", ButtonKind.Secondary, enabled = !state.isRunning) { actions.run { refresh() } }
            GlassButton("Update all", ButtonKind.Primary, Modifier.testTag("update-all"), enabled = updates.isNotEmpty() && !state.isRunning) { confirming = true }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PageContent, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (updates.isEmpty()) item { EmptyNote("Everything is up to date") }
            items(updates, key = { "update-${it.name}" }) { UpdateCard(it, state, actions, navigation) }
            if (state.held.isNotEmpty()) item { SectionTitle("Pinned · held by ketch.lock", Modifier.padding(top = 14.dp), small = true) }
            items(state.held, key = { "held-${it.name}" }) { upgrade ->
                val by = upgrade.heldBy?.let { "pinned in ${tilde(it)}" } ?: "pinned"
                ListRow(
                    upgrade.name,
                    "${upgrade.installed} → ${upgrade.latest} · $by",
                    Modifier.testTag("held-${upgrade.name}"),
                    icon = { LetterIcon(upgrade.name, IconSize.Small) },
                    badge = { Badge("Pinned", BadgeKind.Neutral) },
                )
            }
            if (updates.isNotEmpty()) item { Verified() }
        }
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
    val glass = LocalGlass.current
    val note = changelogNote(upgrade, actions)
    val repo = state.installed.firstOrNull { it.name == upgrade.name }?.let { githubRepo(it.source) }
    Row(
        Modifier
            .fillMaxWidth()
            .tilt()
            .glass(Pane.Card)
            .padding(26.dp)
            .testTag("update-${upgrade.name}"),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LetterIcon(upgrade.name, IconSize.Large)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(upgrade.name, color = glass.ink, style = display(CARD_TITLE))
                Tag(upgrade.latest, tilt = TAG_TILT)
            }
            Text(listOfNotNull(repo, "${upgrade.installed} → ${upgrade.latest}").joinToString(" · "), color = glass.ink2, style = mono(META))
            note?.let { Text(it, color = glass.ink2, style = body(TextSize.BODY), maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Changelog", ButtonKind.Ghost) { navigation.open = upgrade.name }
                GlassButton("Update", ButtonKind.Attention, enabled = !state.isRunning) { actions.run { upgrade(listOf(upgrade.name)) } }
            }
        }
    }
}

@Composable
private fun Verified() {
    val glass = LocalGlass.current
    Row(Modifier.fillMaxWidth().glass(Pane.Flat).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Shield, glass.accentInk, 22.dp)
        Text(VERIFIED_NOTE, color = glass.ink2, style = body(TextSize.BODY, FontWeight.Normal))
    }
}

private const val CARD_TITLE = 20
private const val META = 12
private const val TAG_TILT = -6f
