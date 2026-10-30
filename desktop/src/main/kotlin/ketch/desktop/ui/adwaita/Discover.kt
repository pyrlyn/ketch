// The storefront: the hero and what ketch is about while nothing is asked, and tiles for what the registry and GitHub
// know. The menu's sources narrow it to one of the two; the search bar asks.
package ketch.desktop.ui.adwaita

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.HERO_TITLE
import ketch.desktop.ui.Kind
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Section
import ketch.desktop.ui.Source
import ketch.desktop.ui.copyToClipboard
import ketch.desktop.ui.emptyWords
import ketch.desktop.ui.glass.Mark
import ketch.desktop.ui.screens.PackageTab
import ketch.desktop.ui.screens.githubRepo
import ketch.wire.RegistryPackage
import ketch.wire.Repository
import ketch.wire.Settings
import pyrlyn.adwaita.ActionRow
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.Chips
import pyrlyn.adwaita.CommandLine
import pyrlyn.adwaita.EmptyNote
import pyrlyn.adwaita.Eyebrow
import pyrlyn.adwaita.HeroScene
import pyrlyn.adwaita.IconGradient
import pyrlyn.adwaita.IconSphere
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.OkLabel
import pyrlyn.adwaita.PageHead
import pyrlyn.adwaita.PanelTint
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.PreferencesGroup
import pyrlyn.adwaita.RowLine
import pyrlyn.adwaita.RowText
import pyrlyn.adwaita.RowTitle
import pyrlyn.adwaita.TextLink
import pyrlyn.adwaita.Tile
import pyrlyn.adwaita.TintedPanel
import pyrlyn.glass.AutoGrid
import pyrlyn.glass.Glyph

@Composable
fun AdwaitaDiscover(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    config: Settings?,
    query: String,
) {
    val source = navigation.source
    var kind by remember { mutableStateOf(Kind.All) }
    val known = if (source == Source.Releases) emptyList() else state.search.known.filter(kind.admits)
    // A repository says nothing about what it ships, so only All can claim to hold it.
    val repositories = if (source == Source.Registry || kind != Kind.All) emptyList() else state.search.repositories
    val opening = query.isBlank() && source == null
    AdwaitaPage("discover") {
        if (opening) {
            Hero(state.search.known.firstOrNull(), navigation, config)
        } else {
            PageHead(source?.title ?: "Results")
        }
        if (source != Source.Releases) {
            Chips(Kind.entries, kind, { it.title }, optionModifier = { Modifier.testTag("kind-${it.name}") }) { kind = it }
        }
        if (opening) WhyKetch()
        if (known.isEmpty() && repositories.isEmpty()) EmptyNote(emptyWords(query, source, kind))
        if (known.isNotEmpty()) KnownPackages(known, state, actions, navigation)
        if (repositories.isNotEmpty()) Repositories(repositories, state, actions)
    }
}

@Composable
private fun KnownPackages(
    known: List<RegistryPackage>,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    PreferencesGroup("In the registry", suffix = { if (navigation.source == null) TextLink("See all") { navigation.show(Source.Registry) } }) {
        AutoGrid(known.size, TILE_WIDTH, TILE_GAP) { KnownTile(known[it], state, actions, navigation) }
    }
}

@Composable
private fun Repositories(
    repositories: List<Repository>,
    state: StoreState,
    actions: Actions,
) {
    PreferencesGroup("On GitHub") {
        AutoGrid(repositories.size, TILE_WIDTH, TILE_GAP) { index ->
            val repo = repositories[index]
            val name = repo.spec.removePrefix("github:")
            Tile(
                name.substringAfterLast('/'),
                repo.description ?: listOfNotNull(name, repo.stars?.let { "★ $it" }).joinToString(" · "),
                Modifier.testTag("discover-${repo.spec}"),
                leading = { PackageAvatar(name.substringAfterLast('/')) },
                trailing = { InstallPill(state) { actions.install(repo.spec) } },
            )
        }
    }
}

@Composable
private fun KnownTile(
    pkg: RegistryPackage,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    Tile(
        pkg.name,
        pkg.description ?: listOfNotNull(githubRepo(pkg.source), pkg.latest).joinToString(" · "),
        Modifier.testTag("discover-${pkg.name}"),
        onClick = { navigation.open = pkg.name },
        leading = { PackageAvatar(pkg.name) },
        trailing = {
            when {
                state.activity?.packages?.containsKey(pkg.name) == true -> PillButton("Installing…", busy = true, enabled = false) {}
                state.outdatedVersion(pkg.name) != null -> PillButton("Update", Modifier.widthIn(min = BUTTON_WIDTH), PillStyle.Update) { navigation.show(Section.Updates) }
                state.installed.any { it.name == pkg.name } -> OkLabel("Installed")
                else -> InstallPill(state) { actions.install(pkg.name) }
            }
        },
    )
}

@Composable
private fun InstallPill(
    state: StoreState,
    onInstall: () -> Unit,
) = PillButton("Install", Modifier.widthIn(min = BUTTON_WIDTH), PillStyle.Accent, enabled = !state.isRunning, onClick = onInstall)

@Composable
private fun Hero(
    pick: RegistryPackage?,
    navigation: Navigation,
    config: Settings?,
) {
    val c = LocalAdwaita.current
    val type = LocalAdwaitaType.current
    val name = pick?.name ?: "ketch"
    TintedPanel(PanelTint.Mint) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= SCENE_ROOM
            Row(Modifier.padding(horizontal = 40.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Eyebrow("Featured release")
                    Text(HERO_TITLE, color = c.ink, style = type.title1, modifier = Modifier.widthIn(max = HERO_WIDTH))
                    Text(HERO_WORDS, color = c.ink2, style = type.lead, modifier = Modifier.widthIn(max = HERO_WIDTH))
                    pick?.let {
                        val command = "ketch install ${githubRepo(it.source) ?: it.name}"
                        CommandLine(command, "Copy the command", Modifier.widthIn(max = COMMAND_WIDTH).fillMaxWidth()) { copyToClipboard(command) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("See ${it.name}", style = PillStyle.Accent, size = PillSize.Large) { navigation.open = it.name }
                            PillButton("How ketch picks the asset", size = PillSize.Large) { navigation.open(it.name, PackageTab.Overview) }
                        }
                    }
                }
                if (roomy) {
                    // The words are the idea of an install, not a real run.
                    HeroScene(
                        "resolve",
                        pick?.latest ?: "latest",
                        listOf(config?.target ?: "the asset for this machine", "sha256 ✓ verified", "link ~/.ketch/bin/$name"),
                        pick?.latest ?: "latest",
                    ) { Mark(SCENE_MARK, tile = false) }
                }
            }
        }
    }
}

@Composable
private fun WhyKetch() {
    PreferencesGroup("Why ketch") {
        BoxedList {
            FEATURES.forEachIndexed { index, feature ->
                if (index > 0) RowLine()
                ActionRow(leading = { IconSphere(feature.glyph, feature.gradient) }) {
                    RowTitle(feature.title)
                    RowText(feature.text)
                }
            }
        }
    }
}

private class Feature(
    val title: String,
    val text: String,
    val gradient: IconGradient,
    val glyph: Glyph,
)

private val FEATURES =
    listOf(
        Feature(
            "Apps, not just binaries",
            "An AppImage or tarball app lands in its own folder with a .desktop entry, and leaves cleanly on uninstall.",
            IconGradient.Blue,
            Glyph.Window,
        ),
        Feature("Rollback without a re-download", "An upgrade keeps the previous version on disk, so going back is one relink away.", IconGradient.Orange, Glyph.Undo),
        Feature("Reproducible machines", "ketch.lock pins every package; sync installs exactly what it names.", IconGradient.Mint, Glyph.Lock),
    )

private const val HERO_WORDS = "No PPAs, no build step. ketch takes the asset a project already ships, checks its published checksum and links it into ~/.ketch/bin."
private val TILE_WIDTH = 340.dp
private val TILE_GAP = 12.dp
private val BUTTON_WIDTH = 80.dp
private val HERO_WIDTH = 440.dp
private val COMMAND_WIDTH = 400.dp
private val SCENE_ROOM = 760.dp
private val SCENE_MARK = 64.dp
