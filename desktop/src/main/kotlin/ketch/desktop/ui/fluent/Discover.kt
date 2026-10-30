// The storefront: the hero and what ketch is about while nothing is asked, and tiles for what the registry and GitHub
// know. The navigation view's sources narrow it to one of the two; the title bar's search asks.
package ketch.desktop.ui.fluent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.ui.Actions
import ketch.desktop.ui.HERO_TEXT
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
import pyrlyn.fluent.AppIconSize
import pyrlyn.fluent.BusyButton
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.CommandField
import pyrlyn.fluent.Corners
import pyrlyn.fluent.DoneMark
import pyrlyn.fluent.EmptyNote
import pyrlyn.fluent.Eyebrow
import pyrlyn.fluent.FeatureCard
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.FluentLink
import pyrlyn.fluent.HeroPanel
import pyrlyn.fluent.HeroScene
import pyrlyn.fluent.IconGradient
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.PageHead
import pyrlyn.fluent.PageTitle
import pyrlyn.fluent.SectionHead
import pyrlyn.fluent.SelectorBar
import pyrlyn.fluent.Tile
import pyrlyn.glass.AutoGrid
import pyrlyn.glass.Glyph

@Composable
fun FluentDiscover(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    query: String,
) {
    val source = navigation.source
    var kind by remember { mutableStateOf(Kind.All) }
    val known = if (source == Source.Releases) emptyList() else state.search.known.filter(kind.admits)
    // A repository says nothing about what it ships, so only All can claim to hold it.
    val repositories = if (source == Source.Registry || kind != Kind.All) emptyList() else state.search.repositories
    LazyVerticalGrid(
        GridCells.Adaptive(TILE_WIDTH),
        Modifier.fillMaxSize().testTag("page-discover"),
        contentPadding = PageContent,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        block {
            PageHead(
                title = { PageTitle(source?.title ?: "Discover") },
                actions = {
                    if (source != Source.Releases) {
                        SelectorBar(Kind.entries, kind, { it.title }, itemModifier = { Modifier.testTag("kind-${it.name}") }) { kind = it }
                    }
                },
            )
        }
        if (query.isBlank() && source == null) {
            block { Hero(state.search.known.firstOrNull(), navigation) }
            block { Features() }
        }
        if (known.isEmpty() && repositories.isEmpty()) block { EmptyNote(emptyWords(query, source, kind)) }
        knownPackages(known, query, state, actions, navigation)
        repositoryTiles(repositories, state, actions)
    }
}

private fun LazyGridScope.knownPackages(
    known: List<RegistryPackage>,
    query: String,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    if (known.isNotEmpty()) {
        wide {
            SectionHead(if (query.isBlank()) "In the registry" else "Results", large = true) {
                if (navigation.source == null) FluentLink("See all", glyph = Glyph.Forward) { navigation.show(Source.Registry) }
            }
        }
    }
    items(known, key = { "known-${it.name}" }) { KnownTile(it, state, actions, navigation) }
}

private fun LazyGridScope.repositoryTiles(
    repositories: List<Repository>,
    state: StoreState,
    actions: Actions,
) {
    if (repositories.isNotEmpty()) wide { SectionHead("On GitHub", Modifier.padding(top = PageGap - TILE_GAP), large = true) }
    items(repositories, key = { "repo-${it.spec}" }) { repo ->
        val name = repo.spec.removePrefix("github:")
        PackageTile(
            name.substringAfterLast('/'),
            listOfNotNull(name, repo.stars?.let { "★ $it" }).joinToString(" · "),
            repo.description,
            Modifier.testTag("discover-${repo.spec}"),
        ) { FluentButton("Get", ButtonStyle.Accent, Modifier.widthIn(min = BUTTON_WIDTH), enabled = !state.isRunning) { actions.install(repo.spec) } }
    }
}

private fun LazyGridScope.wide(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** A whole-width part of the page, standing the page's gap from the next one where the grid's own is narrower. */
private fun LazyGridScope.block(content: @Composable () -> Unit) = wide { Column(Modifier.padding(bottom = PageGap - TILE_GAP)) { content() } }

@Composable
private fun KnownTile(
    pkg: RegistryPackage,
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    PackageTile(
        pkg.name,
        listOfNotNull(githubRepo(pkg.source), pkg.latest).joinToString(" · "),
        pkg.description,
        Modifier.testTag("discover-${pkg.name}").clip(Corners.Overlay).clickable { navigation.open = pkg.name },
    ) {
        when {
            state.activity?.packages?.containsKey(pkg.name) == true -> BusyButton("Installing…")
            state.outdatedVersion(pkg.name) != null -> FluentButton("Update", ButtonStyle.Update, Modifier.widthIn(min = BUTTON_WIDTH)) { navigation.show(Section.Updates) }
            state.installed.any { it.name == pkg.name } -> DoneMark("Installed")
            else -> FluentButton("Get", ButtonStyle.Accent, Modifier.widthIn(min = BUTTON_WIDTH), enabled = !state.isRunning) { actions.install(pkg.name) }
        }
    }
}

@Composable
private fun PackageTile(
    name: String,
    meta: String,
    description: String?,
    modifier: Modifier,
    action: @Composable () -> Unit,
) {
    val c = LocalFluent.current
    val type = LocalFluentType.current
    Tile(modifier) { hovered ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PackageIcon(name, AppIconSize.Medium, raised = hovered)
            Column {
                Text(name, color = c.ink, style = type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, color = c.ink2, style = type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(description.orEmpty(), color = c.ink2, style = type.small, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        action()
    }
}

@Composable
private fun Hero(
    pick: RegistryPackage?,
    navigation: Navigation,
) {
    val type = LocalFluentType.current
    val name = pick?.name ?: "ketch"
    val repo = pick?.let { githubRepo(it.source) } ?: name
    HeroPanel(
        scene = {
            // The words are the idea of an install, not a real run.
            HeroScene(
                listOf("resolve" to "$repo → latest", "asset" to "the archive for this PC", "sha256" to "✓ matches published sum", "link" to "$name onto PATH"),
                pick?.latest ?: "latest",
                "SHA-256 verified",
            ) { Mark(SCENE_MARK, tile = false) }
        },
    ) {
        Eyebrow("Featured release")
        Text(HERO_TITLE, color = Color.White, style = type.hero, modifier = Modifier.widthIn(max = 520.dp))
        Text(HERO_TEXT, color = HERO_INK, style = type.body.copy(fontSize = type.strong.fontSize), modifier = Modifier.widthIn(max = 470.dp))
        pick?.let {
            val command = "ketch install ${githubRepo(it.source) ?: it.name}"
            CommandField(command, "Copy the command", Modifier.widthIn(max = 470.dp).fillMaxWidth()) { copyToClipboard(command) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FluentButton("See ${it.name}", ButtonStyle.Accent, large = true) { navigation.open = it.name }
                FluentButton("How ketch picks the asset", ButtonStyle.Glass, large = true) { navigation.open(it.name, PackageTab.Overview) }
            }
        }
    }
}

@Composable
private fun Features() {
    AutoGrid(FEATURES.size, FEATURE_WIDTH, TILE_GAP) { index ->
        val feature = FEATURES[index]
        FeatureCard(feature.glyph, feature.gradient, feature.title, feature.text)
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
            "An installer or portable app lands in its own folder with a Start menu entry, and is removed cleanly on uninstall.",
            IconGradient.Blue,
            Glyph.Window,
        ),
        Feature("Rollback without a re-download", "An upgrade keeps the previous version on disk, so going back is one relink away.", IconGradient.Orange, Glyph.Undo),
        Feature("Reproducible machines", "ketch.lock pins every package; sync installs exactly what it names.", IconGradient.Mint, Glyph.Lock),
    )

private val TILE_WIDTH = 280.dp
private val TILE_GAP = 12.dp
private val FEATURE_WIDTH = 250.dp
private val BUTTON_WIDTH = 88.dp
private val SCENE_MARK = 96.dp
private val HERO_INK = Color(0xDBFFFFFF)
