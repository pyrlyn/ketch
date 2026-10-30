// A package's page: its header, the tabs, and the facts beside them when there is room.
package ketch.desktop.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.ChangelogText
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.screens.ChangelogRead
import ketch.desktop.ui.screens.PackageFacts
import ketch.desktop.ui.screens.PackageTab
import ketch.desktop.ui.screens.UninstallConfirm
import ketch.desktop.ui.screens.readChangelog
import ketch.wire.Changelog
import ketch.wire.ChangelogSource
import ketch.wire.Package
import pyrlyn.glass.Badge
import pyrlyn.glass.BadgeKind
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.CommandField
import pyrlyn.glass.FactChip
import pyrlyn.glass.Facts
import pyrlyn.glass.GlassButton
import pyrlyn.glass.IconSize
import pyrlyn.glass.LetterIcon
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.Pane
import pyrlyn.glass.SectionTitle
import pyrlyn.glass.Segmented
import pyrlyn.glass.Spinner
import pyrlyn.glass.Tag
import pyrlyn.glass.TextSize
import pyrlyn.glass.body
import pyrlyn.glass.display
import pyrlyn.glass.glass
import pyrlyn.glass.mono

@Composable
fun GlassPackage(
    name: String,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val facts = PackageFacts(name, state)
    var tab by remember(name) { mutableStateOf(PackageTab.Changelog) }
    var uninstalling by remember(name) { mutableStateOf(false) }
    val changelog = readChangelog(facts, actions)
    GlassPage(name, "package", navigation, crumb = navigation.section.title) {
        PageColumn {
            Header(facts, state, actions) { uninstalling = true }
            Segmented(PackageTab.entries, tab, { it.title }) { tab = it }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= TWO_COLUMNS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Tab(tab, facts, changelog, Modifier.weight(1f))
                        Details(facts, Modifier.width(ASIDE_WIDTH))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Tab(tab, facts, changelog, Modifier.fillMaxWidth())
                        Details(facts, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    // The uninstall runs on the window's scope; the page of a package that is going has nothing left to show.
    val installed = facts.installed
    if (uninstalling && installed != null) UninstallConfirm(installed, actions, onUninstall = { navigation.open = null }) { uninstalling = false }
}

@Composable
private fun Header(
    facts: PackageFacts,
    state: StoreState,
    actions: Actions,
    onUninstall: () -> Unit,
) {
    val glass = LocalGlass.current
    val uri = LocalUriHandler.current
    val installed = facts.installed
    Row(Modifier.fillMaxWidth().glass(Pane.Card).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        LetterIcon(facts.name, IconSize.Large)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(facts.name, color = glass.ink, style = display(NAME))
                (facts.update ?: installed?.version ?: facts.latest)?.let { Tag(it, tilt = TAG_TILT) }
            }
            facts.repo?.let { Text(it, color = glass.ink2, style = mono(REPO)) }
            facts.registry?.description?.let { Text(it, color = glass.ink2, style = body(DESCRIPTION)) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    installed != null && facts.update != null -> {
                        GlassButton("Update to ${facts.update}", ButtonKind.Primary, enabled = !state.isRunning) { actions.run { upgrade(listOf(facts.name)) } }
                    }

                    installed == null -> {
                        GlassButton("Install", ButtonKind.Primary, enabled = !state.isRunning) { actions.install(facts.name) }
                    }
                }
                facts.repo?.let { GlassButton("Open on GitHub", ButtonKind.Ghost) { uri.openUri("https://github.com/$it") } }
                Spacer(Modifier.weight(1f))
                if (installed != null) GlassButton("Uninstall", ButtonKind.Danger, enabled = !state.isRunning, onClick = onUninstall)
            }
        }
    }
}

@Composable
private fun Tab(
    tab: PackageTab,
    facts: PackageFacts,
    changelog: ChangelogRead,
    modifier: Modifier,
) {
    Column(
        modifier.glass(Pane.Flat).padding(22.dp).testTag("package-${tab.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        when (tab) {
            PackageTab.Overview -> Overview(facts)
            PackageTab.Changelog -> Changes(changelog, facts.installed?.version)
            PackageTab.Files -> Files(facts.installed)
        }
    }
}

@Composable
private fun Overview(facts: PackageFacts) {
    val glass = LocalGlass.current
    val command = "ketch install ${facts.repo ?: facts.name}"
    Text(facts.registry?.description ?: "No description.", color = glass.ink, style = body(TextSize.BODY))
    CommandField(command, "Copy the command", Modifier.fillMaxWidth(), fill = TERMINAL) { copyToClipboard(command) }
}

@Composable
private fun Changes(
    changelog: ChangelogRead,
    installed: String?,
) {
    val glass = LocalGlass.current
    val entries = changelog.entries
    when {
        changelog.loading -> {
            Spinner()
        }

        entries.isNullOrEmpty() -> {
            Text("No changelog found.", color = glass.ink2, style = body(TextSize.BODY))
        }

        else -> {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(glass.separator))
                Article(entry, installed)
            }
        }
    }
}

@Composable
private fun Article(
    entry: Changelog,
    installed: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(entry.heading ?: entry.version, small = true)
            FactChip(origin(entry.source))
            if (entry.version == installed) Badge("Installed", BadgeKind.Neutral)
        }
        ChangelogText(entry.body)
    }
}

private fun origin(source: ChangelogSource) = if (source is ChangelogSource.File) source.path.substringAfterLast('/') else "release notes"

@Composable
private fun Files(installed: Package?) {
    val glass = LocalGlass.current
    if (installed == null) {
        Text("Not installed.", color = glass.ink2, style = body(TextSize.BODY))
        return
    }
    Facts(installed.binaries.map { "Binary" to tilde(it) } + ("Store" to tilde(installed.prefix)))
    RevealButton(installed.binaries.firstOrNull() ?: installed.prefix)
}

@Composable
private fun Details(
    facts: PackageFacts,
    modifier: Modifier,
) {
    val glass = LocalGlass.current
    val installed = facts.installed
    val rows =
        listOfNotNull(
            installed?.let { "Installed" to it.version },
            facts.latest?.let { "Latest" to it },
            installed?.let { "Source" to it.source },
            facts.repo?.let { "Repository" to it },
            installed?.let { linkedTo(it.binaries) }?.let { "Linked to" to it },
            installed?.retained?.takeIf { it.isNotEmpty() }?.let { "Kept" to it.joinToString(", ") },
        )
    Column(modifier.glass(Pane.Flat).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Details", color = glass.ink, style = body(TextSize.HEADING, FontWeight.SemiBold))
        Facts(rows, labelWidth = FACT_LABEL)
        installed?.trust?.let { trust ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TrustBadge(trust)
                Text("trust: $trust", color = glass.ink2, style = body(TRUST))
            }
        }
    }
}

@Composable
private fun TrustBadge(trust: String) {
    when (trust) {
        "checksum" -> Badge("SHA-256 verified", BadgeKind.Success)
        "signed" -> Badge("Signed", BadgeKind.Success)
        else -> Badge("First use", BadgeKind.Warning)
    }
}

private const val NAME = 30
private const val REPO = 13
private const val DESCRIPTION = 14f
private const val TRUST = 12f
private const val TAG_TILT = -12f
private val TERMINAL = Color(0xFF0A1214)
private val TWO_COLUMNS = 760.dp
private val ASIDE_WIDTH = 288.dp
private val FACT_LABEL = 84.dp
