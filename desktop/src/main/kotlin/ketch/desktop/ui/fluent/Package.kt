// A package's page: the way back, its header, the views, and the facts beside them when there is room.
package ketch.desktop.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.ChangelogText
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.changelogOrigin
import ketch.desktop.ui.checksumWords
import ketch.desktop.ui.copyToClipboard
import ketch.desktop.ui.packageDetails
import ketch.desktop.ui.screens.ChangelogRead
import ketch.desktop.ui.screens.PackageFacts
import ketch.desktop.ui.screens.PackageTab
import ketch.desktop.ui.screens.UninstallConfirm
import ketch.desktop.ui.screens.readChangelog
import ketch.desktop.ui.tilde
import ketch.desktop.ui.trustSeal
import ketch.wire.Changelog
import ketch.wire.Package
import ketch.wire.Resolution
import pyrlyn.fluent.AppIconSize
import pyrlyn.fluent.BadgeKind
import pyrlyn.fluent.Breadcrumb
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.CommandField
import pyrlyn.fluent.FactsList
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.LiftPanel
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.Panel
import pyrlyn.fluent.Pill
import pyrlyn.fluent.ProgressRing
import pyrlyn.fluent.ReleaseTag
import pyrlyn.fluent.SelectorBar
import pyrlyn.fluent.StatusBadge
import pyrlyn.fluent.TerminalLine
import pyrlyn.fluent.TerminalOutput

@Composable
fun FluentPackage(
    name: String,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    val facts = PackageFacts(name, state)
    var tab by remember(name) { mutableStateOf(navigation.takeFirstTab()) }
    var uninstalling by remember(name) { mutableStateOf(false) }
    val changelog = readChangelog(facts, actions)
    FluentPage("package") {
        Breadcrumb(navigation.section.title, name) { navigation.open = null }
        Header(facts, state, actions) { uninstalling = true }
        SelectorBar(PackageTab.entries, tab, { it.title }) { tab = it }
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
    val c = LocalFluent.current
    val type = LocalFluentType.current
    val uri = LocalUriHandler.current
    val installed = facts.installed
    LiftPanel(Modifier.fillMaxWidth()) { hovered ->
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            PackageIcon(facts.name, AppIconSize.Large, raised = hovered)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(facts.name, color = c.ink, style = type.name)
                    facts.update?.let { StatusBadge("Update to $it available", BadgeKind.Update) }
                }
                facts.repo?.let { Text(it, color = c.ink2, style = type.code) }
                facts.registry?.description?.let { Text(it, color = c.ink2, style = type.body) }
            }
            (facts.update ?: installed?.version ?: facts.latest)?.let {
                ReleaseTag(it, Modifier.offset(y = TAG_RISE).heightIn(min = TAG_HEIGHT), tilt = TAG_TILT, style = type.code.copy(fontSize = TAG_TEXT, fontWeight = FontWeight.SemiBold))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                installed != null && facts.update != null -> {
                    FluentButton("Update to ${facts.update}", ButtonStyle.Accent, enabled = !state.isRunning) { actions.run { upgrade(listOf(facts.name)) } }
                }

                installed == null -> {
                    FluentButton("Install", ButtonStyle.Accent, enabled = !state.isRunning) { actions.install(facts.name) }
                }
            }
            facts.repo?.let { FluentButton("Open on GitHub") { uri.openUri("https://github.com/$it") } }
            Spacer(Modifier.weight(1f))
            if (installed != null) FluentButton("Uninstall", ButtonStyle.Danger, enabled = !state.isRunning, onClick = onUninstall)
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
    Panel(modifier.testTag("package-${tab.name.lowercase()}"), spacing = 18.dp) {
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
    Text(facts.registry?.description ?: "No description.", color = LocalFluent.current.ink, style = LocalFluentType.current.body)
    CommandField(command, "Copy the command", Modifier.fillMaxWidth()) { copyToClipboard(command) }
    Why(facts.name, actions)
}

/** What `ketch why` says: asked only here, since it goes to the package's source. */
@Composable
private fun Why(
    name: String,
    actions: Actions,
) {
    var asked by remember(name) { mutableStateOf(false) }
    var resolution by remember(name) { mutableStateOf<Resolution?>(null) }
    // Keyed on the name: a page that moved on cancels the question, and its reply never lands on the next package.
    LaunchedEffect(name) {
        resolution = actions.store.why(name)
        asked = true
    }
    val found = resolution
    when {
        !asked -> {
            ProgressRing()
        }

        found == null -> {
            Text("ketch could not ask this package's source.", color = LocalFluent.current.ink2, style = LocalFluentType.current.body)
        }

        else -> {
            SelectionContainer { TerminalOutput("ketch why $name", whyLines(found), Modifier.fillMaxWidth().testTag("package-why")) }
        }
    }
}

private fun whyLines(found: Resolution) =
    listOfNotNull(
        TerminalLine("resolve", "${found.source} → ${found.version ?: "no release"}", good = found.version != null),
        TerminalLine("asset", found.asset ?: "none fits this machine"),
        found.reason?.let { TerminalLine("reason", it) },
        TerminalLine("sha256", checksumWords(found.checksum)),
    )

@Composable
private fun Changes(
    changelog: ChangelogRead,
    installed: String?,
) {
    val c = LocalFluent.current
    val entries = changelog.entries
    when {
        changelog.loading -> {
            ProgressRing()
        }

        entries.isNullOrEmpty() -> {
            Text("No changelog found.", color = c.ink2, style = LocalFluentType.current.body)
        }

        else -> {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.stroke))
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
    val type = LocalFluentType.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.heading ?: entry.version, color = LocalFluent.current.ink, style = type.strong.copy(fontSize = ARTICLE_HEAD))
            Pill(changelogOrigin(entry.source))
            if (entry.version == installed) StatusBadge("Installed", BadgeKind.Neutral)
        }
        ChangelogText(entry.body)
    }
}

@Composable
private fun Files(installed: Package?) {
    if (installed == null) {
        Text("Not installed.", color = LocalFluent.current.ink2, style = LocalFluentType.current.body)
        return
    }
    FactsList(installed.binaries.map { "Binary" to tilde(it) } + ("Store" to tilde(installed.prefix)))
    RevealButton(installed.binaries.firstOrNull() ?: installed.prefix)
}

@Composable
private fun Details(
    facts: PackageFacts,
    modifier: Modifier,
) {
    val c = LocalFluent.current
    val type = LocalFluentType.current
    Panel(modifier) {
        Text("Details", color = c.ink, style = type.strong)
        FactsList(packageDetails(facts))
        facts.installed?.trust?.let { trust ->
            val (word, checked) = trustSeal(trust)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(word, if (checked) BadgeKind.Success else BadgeKind.Warning)
                Text("trust: $trust", color = c.ink2, style = type.caption)
            }
        }
    }
}

private const val TAG_TILT = -12f
private val TAG_TEXT = 16.sp
private val TAG_HEIGHT = 36.dp
private val TAG_RISE = (-6).dp
private val ARTICLE_HEAD = 17.sp
private val ASIDE_WIDTH = 300.dp
private val COLUMN_GAP = 16.dp
private val TWO_COLUMNS = 736.dp
