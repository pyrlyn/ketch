// A package's page: what it is, what is installed and what is newer, its changelog and its files, and the buttons
// that change it. It shows a package that is not installed too, from the registry or a `ketch://` link.
package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.ChangelogText
import ketch.desktop.ui.Confirm
import ketch.desktop.ui.LocalMonoStyle
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.checksumWords
import ketch.wire.Changelog
import ketch.wire.Package
import ketch.wire.Resolution

enum class PackageTab(
    val title: String,
) {
    Overview("Overview"),
    Changelog("Changelog"),
    Files("Files"),
}

/** What the store knows of [name], installed or not. */
class PackageFacts(
    val name: String,
    state: StoreState,
) {
    val installed = state.installed.firstOrNull { it.name == name }
    val registry = state.search.known.firstOrNull { it.name == name }
    val update = state.outdatedVersion(name)
    val latest = update ?: registry?.latest
    val repo = githubRepo(installed?.source ?: registry?.source.orEmpty())
}

/** The changelog a package's page shows, null when the core has none to give. */
class ChangelogRead(
    val loading: Boolean,
    val entries: List<Changelog>?,
)

@Composable
fun readChangelog(
    facts: PackageFacts,
    actions: Actions,
): ChangelogRead {
    var read by remember(facts.name) { mutableStateOf(ChangelogRead(loading = true, entries = null)) }
    // Keyed on the name: a page that moved on cancels the read, and its reply never lands on the next package.
    LaunchedEffect(facts.name, facts.installed?.version, facts.latest) {
        read = ChangelogRead(loading = true, entries = read.entries)
        read = ChangelogRead(loading = false, entries = actions.store.changelog(facts.name, facts.installed?.version, facts.latest))
    }
    return read
}

/** What `ketch why` has said so far. */
sealed interface WhyRead {
    data object Asking : WhyRead

    data object Unanswered : WhyRead

    data class Answered(
        val resolution: Resolution,
    ) : WhyRead
}

@Composable
fun readWhy(
    name: String,
    actions: Actions,
): WhyRead {
    var read by remember(name) { mutableStateOf<WhyRead>(WhyRead.Asking) }
    // Keyed on the name: a page that moved on cancels the question, and its reply never lands on the next package.
    LaunchedEffect(name) { read = actions.store.why(name)?.let(WhyRead::Answered) ?: WhyRead.Unanswered }
    return read
}

const val WHY_RESOLVE = "resolve"

/** The lines of `ketch why`, each a key and what it says. */
fun whyFacts(found: Resolution) =
    listOfNotNull(
        WHY_RESOLVE to "${found.source} → ${found.version ?: "no release"}",
        "asset" to (found.asset ?: "none fits this machine"),
        found.reason?.let { "reason" to it },
        "sha256" to checksumWords(found.checksum),
    )

@Composable
fun PackageScreen(
    name: String,
    state: StoreState,
    actions: Actions,
    onBack: () -> Unit,
) {
    val facts = PackageFacts(name, state)
    val installed = facts.installed
    var tab by remember(name) { mutableStateOf(PackageTab.Changelog) }
    var uninstalling by remember(name) { mutableStateOf(false) }
    val changelog = readChangelog(facts, actions)
    val space = LocalSpacing.current
    Page(
        name,
        "package",
        buttons = { TextButton(onClick = onBack) { Text("Back") } },
    ) {
        Text(summary(facts.repo, installed, facts.update, facts.latest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(vertical = space.md), horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            Buttons(name, state, installed, facts.update, facts.repo, actions) { uninstalling = true }
        }
        PrimaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.width(TABS_WIDTH.dp)) {
            for (each in PackageTab.entries) Tab(selected = each == tab, onClick = { tab = each }, text = { Text(each.title) })
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = space.lg)
                .testTag("package-${tab.name.lowercase()}"),
        ) {
            when (tab) {
                PackageTab.Overview -> Overview(facts.registry?.description, installed, facts.latest, facts.repo)
                PackageTab.Changelog -> Changes(changelog.loading, changelog.entries)
                PackageTab.Files -> Files(installed)
            }
        }
    }
    // The uninstall runs on the window's scope; the page of a package that is going has nothing left to show.
    if (uninstalling && installed != null) UninstallConfirm(installed, actions, onUninstall = onBack) { uninstalling = false }
}

@Composable
private fun Buttons(
    name: String,
    state: StoreState,
    installed: Package?,
    update: String?,
    repo: String?,
    actions: Actions,
    onUninstall: () -> Unit,
) {
    val uri = LocalUriHandler.current
    when {
        installed != null && update != null -> Button(onClick = { actions.run { upgrade(listOf(name)) } }, enabled = !state.isRunning) { Text("Update to $update") }
        installed == null -> Button(onClick = { actions.install(name) }, enabled = !state.isRunning) { Text("Install") }
    }
    repo?.let { OutlinedButton(onClick = { uri.openUri("https://github.com/$it") }) { Text("Open on GitHub") } }
    if (installed != null) {
        Button(
            onClick = onUninstall,
            enabled = !state.isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text("Uninstall") }
    }
}

/** `owner/repo · installed 1.0 · 1.1 available`, with whatever is known. */
private fun summary(
    repo: String?,
    installed: Package?,
    update: String?,
    latest: String?,
) = listOfNotNull(
    repo,
    installed?.let { "installed ${it.version}" },
    update?.let { "$it available" } ?: latest?.takeIf { installed == null }?.let { "latest $it" },
).joinToString(" · ")

@Composable
private fun Overview(
    description: String?,
    installed: Package?,
    latest: String?,
    repo: String?,
) {
    Text(description ?: "No description.", style = MaterialTheme.typography.bodyMedium)
    Column(Modifier.padding(top = LocalSpacing.current.lg), verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        Fact("Installed", installed?.version)
        Fact("Latest", latest)
        Fact("Source", installed?.source)
        Fact("Repository", repo)
        Fact("Trust", installed?.trust)
        Fact("Kept", installed?.retained?.takeIf { it.isNotEmpty() }?.joinToString(", "))
    }
}

@Composable
private fun Fact(
    label: String,
    value: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(LABEL_WIDTH.dp))
        SelectionContainer { Text(value ?: "—", style = LocalMonoStyle.current) }
    }
}

@Composable
private fun Changes(
    loading: Boolean,
    changelog: List<Changelog>?,
) {
    when {
        loading -> CircularProgressIndicator()
        changelog.isNullOrEmpty() -> Text("No changelog found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> ChangelogText(changelog.joinToString("\n\n") { "## ${it.heading ?: it.version}\n\n${it.body}" })
    }
}

@Composable
private fun Files(installed: Package?) {
    if (installed == null) {
        Text("Not installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
            Text("Store", style = MaterialTheme.typography.titleSmall)
            Text(installed.prefix, style = LocalMonoStyle.current)
            Text("Links", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = LocalSpacing.current.md))
            installed.binaries.forEach { Text(it, style = LocalMonoStyle.current) }
        }
    }
}

@Composable
fun UninstallConfirm(
    pkg: Package,
    actions: Actions,
    onUninstall: () -> Unit = {},
    onDone: () -> Unit,
) {
    Confirm(
        title = "Uninstall ${pkg.name}?",
        message = "Removes ${pkg.name} ${pkg.version}, its links and its store directory. Its own configuration is kept.",
        confirm = "Uninstall",
        destructive = true,
        subject = pkg.name,
        onConfirm = {
            onDone()
            actions.run { uninstall(listOf(pkg.name)) }
            onUninstall()
        },
        onDismiss = onDone,
    )
}

private const val TABS_WIDTH = 360
private const val LABEL_WIDTH = 96
