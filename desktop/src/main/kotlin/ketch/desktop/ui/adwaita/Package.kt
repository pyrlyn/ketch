package ketch.desktop.ui.adwaita

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.ChangelogText
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.changelogOrigin
import ketch.desktop.ui.copyToClipboard
import ketch.desktop.ui.packageDetails
import ketch.desktop.ui.screens.ChangelogRead
import ketch.desktop.ui.screens.PackageFacts
import ketch.desktop.ui.screens.PackageTab
import ketch.desktop.ui.screens.UninstallConfirm
import ketch.desktop.ui.screens.WhyRead
import ketch.desktop.ui.screens.readChangelog
import ketch.desktop.ui.screens.readWhy
import ketch.desktop.ui.screens.whyFacts
import ketch.desktop.ui.tilde
import ketch.desktop.ui.trustSeal
import ketch.wire.Changelog
import ketch.wire.Package
import pyrlyn.adwaita.AvatarSize
import pyrlyn.adwaita.Breadcrumb
import pyrlyn.adwaita.CommandLine
import pyrlyn.adwaita.Facts
import pyrlyn.adwaita.Heading
import pyrlyn.adwaita.LinkedToggle
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.PanelTint
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.Spinner
import pyrlyn.adwaita.Status
import pyrlyn.adwaita.StatusPill
import pyrlyn.adwaita.Terminal
import pyrlyn.adwaita.TerminalLine
import pyrlyn.adwaita.TintedPanel
import pyrlyn.adwaita.boxed

@Composable
fun AdwaitaPackage(
    name: String,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val facts = PackageFacts(name, state)
    var tab by remember(name) { mutableStateOf(navigation.takeFirstTab()) }
    var uninstalling by remember(name) { mutableStateOf(false) }
    val changelog = readChangelog(facts, actions)
    AdwaitaPage("package") {
        Breadcrumb(navigation.section.label, name) { navigation.open = null }
        Header(facts, state, actions) { uninstalling = true }
        LinkedToggle(PackageTab.entries, tab, { it.title }) { tab = it }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= TWO_COLUMNS) {
                Row(horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    Tab(tab, facts, changelog, actions, Modifier.weight(1f))
                    Details(facts, Modifier.width(ASIDE_WIDTH))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    Tab(tab, facts, changelog, actions, Modifier.fillMaxWidth())
                    Details(facts, Modifier.fillMaxWidth())
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
    val c = LocalAdwaita.current
    val type = LocalAdwaitaType.current
    val uri = LocalUriHandler.current
    val installed = facts.installed
    TintedPanel(PanelTint.Blue) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                PackageAvatar(facts.name, AvatarSize.Large)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(facts.name, color = c.ink, style = type.page)
                        facts.update?.let { StatusPill("Update to $it available", Status.Update) }
                    }
                    facts.repo?.let { Text(it, color = c.ink2, style = type.mono) }
                    facts.registry?.description?.let { Text(it, color = c.ink2, style = type.body) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    installed != null && facts.update != null -> {
                        PillButton("Update to ${facts.update}", style = PillStyle.Update, enabled = !state.isRunning) { actions.run { upgrade(listOf(facts.name)) } }
                    }

                    installed == null -> {
                        PillButton("Install", style = PillStyle.Accent, enabled = !state.isRunning) { actions.install(facts.name) }
                    }
                }
                facts.repo?.let { PillButton("Open on GitHub") { uri.openUri("https://github.com/$it") } }
                Spacer(Modifier.weight(1f))
                if (installed != null) PillButton("Uninstall…", style = PillStyle.FlatDanger, enabled = !state.isRunning, onClick = onUninstall)
            }
        }
    }
}

@Composable
private fun Tab(
    tab: PackageTab,
    facts: PackageFacts,
    changelog: ChangelogRead,
    actions: Actions,
    modifier: Modifier,
) {
    Column(modifier.testTag("package-${tab.name.lowercase()}").boxed().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        when (tab) {
            PackageTab.Overview -> Overview(facts, actions)
            PackageTab.Changelog -> Changes(changelog, facts.installed?.version)
            PackageTab.Files -> Files(facts.installed)
        }
    }
}

@Composable
private fun Overview(
    facts: PackageFacts,
    actions: Actions,
) {
    val command = "ketch install ${facts.repo ?: facts.name}"
    Text(facts.registry?.description ?: "No description.", color = LocalAdwaita.current.ink, style = LocalAdwaitaType.current.body)
    CommandLine(command, "Copy the command", Modifier.fillMaxWidth()) { copyToClipboard(command) }
    Why(facts.name, actions)
}

/** What `ketch why` says: asked only here, since it goes to the package's source. */
@Composable
private fun Why(
    name: String,
    actions: Actions,
) {
    val c = LocalAdwaita.current
    when (val read = readWhy(name, actions)) {
        WhyRead.Asking -> {
            Spinner(c.accent)
        }

        WhyRead.Unanswered -> {
            Text("ketch could not ask this package's source.", color = c.ink2, style = LocalAdwaitaType.current.body)
        }

        is WhyRead.Answered -> {
            SelectionContainer {
                Terminal(
                    listOf(TerminalLine("$ ketch why $name")) +
                        whyFacts(read.resolution).map { (key, value) ->
                            TerminalLine(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = c.accent)) { append(key) }
                                    append("  $value")
                                },
                            )
                        },
                    Modifier.testTag("package-why"),
                )
            }
        }
    }
}

@Composable
private fun Changes(
    changelog: ChangelogRead,
    installed: String?,
) {
    val c = LocalAdwaita.current
    val entries = changelog.entries
    when {
        changelog.loading -> {
            Spinner(c.accent)
        }

        entries.isNullOrEmpty() -> {
            Text("No changelog found.", color = c.ink2, style = LocalAdwaitaType.current.body)
        }

        else -> {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(entry.heading ?: entry.version, color = LocalAdwaita.current.ink, style = LocalAdwaitaType.current.section)
            StatusPill(changelogOrigin(entry.source), Status.Neutral)
            if (entry.version == installed) StatusPill("Installed", Status.Neutral)
        }
        ChangelogText(entry.body)
    }
}

@Composable
private fun Files(installed: Package?) {
    if (installed == null) {
        Text("Not installed.", color = LocalAdwaita.current.ink2, style = LocalAdwaitaType.current.body)
        return
    }
    Facts(installed.binaries.map { "Binary" to tilde(it) } + ("Store" to tilde(installed.prefix)))
    FolderButton(installed.binaries.firstOrNull() ?: installed.prefix)
}

@Composable
private fun Details(
    facts: PackageFacts,
    modifier: Modifier,
) {
    Column(modifier.boxed().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Details")
        Facts(packageDetails(facts))
        facts.installed?.trust?.let { trust ->
            val (word, checked) = trustSeal(trust)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(word, if (checked) Status.Ok else Status.Warning)
                Text("trust: $trust", color = LocalAdwaita.current.ink2, style = LocalAdwaitaType.current.caption)
            }
        }
    }
}

private val ASIDE_WIDTH = 280.dp
private val COLUMN_GAP = 16.dp
private val TWO_COLUMNS = 720.dp
