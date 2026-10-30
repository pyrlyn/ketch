// The storefront: the hero and what ketch is about while nothing is asked, and tiles for what the registry and GitHub
// know. The sidebar's sources narrow it to one of the two.
package ketch.desktop.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.HERO_TEXT
import ketch.desktop.ui.HERO_TITLE
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Section
import ketch.desktop.ui.Source
import ketch.desktop.ui.copyToClipboard
import ketch.desktop.ui.screens.githubRepo
import ketch.desktop.ui.searchFocus
import ketch.wire.RegistryPackage
import org.jetbrains.compose.resources.stringResource
import pyrlyn.glass.AutoGrid
import pyrlyn.glass.Badge
import pyrlyn.glass.BadgeKind
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.CommandField
import pyrlyn.glass.EmptyNote
import pyrlyn.glass.Eyebrow
import pyrlyn.glass.FeatureCard
import pyrlyn.glass.GlassButton
import pyrlyn.glass.GlassColors
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon
import pyrlyn.glass.HeroPanel
import pyrlyn.glass.IconSize
import pyrlyn.glass.LetterIcon
import pyrlyn.glass.SearchField
import pyrlyn.glass.SectionTitle
import pyrlyn.glass.Tag
import pyrlyn.glass.TextSize
import pyrlyn.glass.Tile
import pyrlyn.glass.body
import pyrlyn.glass.display
import pyrlyn.glass.mono

@Composable
fun GlassDiscover(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // A new query cancels the wait for the one before; the store also shows only the newest answer.
    LaunchedEffect(query) { actions.store.searchAfterTyping(query) }
    val source = navigation.source
    val known = if (source == Source.Releases) emptyList() else state.search.known
    val repositories = if (source == Source.Registry) emptyList() else state.search.repositories
    val focus = searchFocus(navigation)
    GlassPage(
        source?.title ?: stringResource(Res.string.section_discover),
        "discover",
        navigation,
        toolbar = { SearchField(query, stringResource(Res.string.discover_search_short), Modifier.testTag("discover-query"), "⌘K", focus) { query = it } },
    ) {
        LazyVerticalGrid(
            GridCells.Adaptive(TILE_WIDTH),
            contentPadding = PageContent,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (query.isBlank() && source == null) {
                wide { Hero(state.search.known.firstOrNull()) { navigation.open = it } }
                wide { Features() }
            }
            if (known.isEmpty() && repositories.isEmpty()) {
                wide { EmptyNote(emptyWords(query, source)) }
            }
            if (known.isNotEmpty()) wide { SectionTitle(if (query.isBlank()) stringResource(Res.string.discover_in_registry) else stringResource(Res.string.discover_results)) }
            items(known, key = { "known-${it.name}" }) { KnownTile(it, state, actions, navigation) }
            if (repositories.isNotEmpty()) wide { SectionTitle(stringResource(Res.string.discover_on_github)) }
            items(repositories, key = { "repo-${it.spec}" }) { repo ->
                val name = repo.spec.removePrefix("github:")
                Tile(
                    name.substringAfterLast('/'),
                    listOfNotNull(name, repo.stars?.let { "★ $it" }).joinToString(" · "),
                    repo.description,
                    Modifier.testTag("discover-${repo.spec}"),
                    icon = { LetterIcon(name.substringAfterLast('/'), IconSize.Medium) },
                    action = { GlassButton(stringResource(Res.string.action_get), ButtonKind.Raised, enabled = !state.isRunning) { actions.install(repo.spec) } },
                )
            }
        }
    }
}

private fun LazyGridScope.wide(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun emptyWords(
    query: String,
    source: Source?,
) = when {
    query.isNotBlank() -> tr(Res.string.discover_no_match, query.trim())
    source == Source.Releases -> tr(Res.string.discover_type_repo)
    else -> tr(Res.string.discover_registry_empty)
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
        listOfNotNull(githubRepo(pkg.source), pkg.latest).joinToString(" · ").ifEmpty { null },
        pkg.description,
        Modifier.testTag("discover-${pkg.name}"),
        onClick = { navigation.open = pkg.name },
        icon = { LetterIcon(pkg.name, IconSize.Medium) },
        action = {
            when {
                state.outdatedVersion(pkg.name) != null -> GlassButton(stringResource(Res.string.action_update), ButtonKind.Attention) { navigation.show(Section.Updates) }
                state.installed.any { it.name == pkg.name } -> Badge(stringResource(Res.string.status_installed), BadgeKind.Success)
                else -> GlassButton(stringResource(Res.string.action_get), ButtonKind.Raised, enabled = !state.isRunning) { actions.install(pkg.name) }
            }
        },
    )
}

@Composable
private fun Hero(
    pick: RegistryPackage?,
    onOpen: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= HERO_ROOMY
        HeroPanel {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Eyebrow(stringResource(Res.string.discover_featured))
                Text(HERO_TITLE, color = Color.White, style = display(HERO_SIZE).copy(lineHeight = 48.sp, letterSpacing = (-1.6).sp), modifier = Modifier.widthIn(max = 520.dp))
                Text(HERO_TEXT, color = HERO_INK, style = body(HERO_BODY).copy(lineHeight = 25.sp), modifier = Modifier.widthIn(max = 460.dp))
                pick?.let {
                    val command = "ketch install ${githubRepo(it.source) ?: it.name}"
                    CommandField(command, stringResource(Res.string.action_copy_command), Modifier.widthIn(max = 460.dp)) { copyToClipboard(command) }
                    GlassButton(stringResource(Res.string.action_see, it.name), ButtonKind.Hero, large = true) { onOpen(it.name) }
                }
            }
            if (roomy) HeroArt(pick, Modifier.weight(1f))
        }
    }
}

/** The steps of an install on a leaning plate. The words are the idea of an install, not a real run. */
@Composable
private fun HeroArt(
    pick: RegistryPackage?,
    modifier: Modifier,
) {
    val name = pick?.name ?: "ketch"
    val repo = pick?.let { githubRepo(it.source) } ?: name
    val lines = listOf(stringResource(Res.string.discover_line_resolve, repo), stringResource(Res.string.discover_line_asset_mac), stringResource(Res.string.discover_line_sum_mac), stringResource(Res.string.discover_line_link_path, name))
    BoxWithConstraints(
        modifier.height(ART_HEIGHT).graphicsLayer {
            rotationX = ART_BACK
            rotationY = ART_TURN
            rotationZ = ART_SPIN
            cameraDistance = ART_DISTANCE * density
        },
    ) {
        val plateShape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .offset(maxWidth * PLATE_X, maxHeight * PLATE_Y)
                .size(maxWidth * PLATE_WIDTH, maxHeight * PLATE_HEIGHT)
                .clip(plateShape)
                .background(Brush.linearGradient(listOf(PLATE_TOP, PLATE_BOTTOM)))
                .border(1.dp, PLATE_RIM, plateShape)
                .padding(start = 22.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lines.forEachIndexed { index, line ->
                Text(line, color = if (index == 0) GlassColors.Mint else GlassColors.TerminalInk.copy(alpha = LINE_FADE), style = mono(ART_TEXT))
            }
        }
        Box(
            Modifier
                .offset(maxWidth * TILE_X, maxHeight * TILE_Y)
                .size(132.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(ART_TILE_TOP, GlassColors.Deep, ART_TILE_BOTTOM))),
            contentAlignment = Alignment.Center,
        ) { Mark(96.dp, tile = false) }
        Tag(pick?.latest ?: stringResource(Res.string.discover_latest), Modifier.offset(maxWidth * TAG_X, maxHeight * TAG_Y))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 12.dp)
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(CHECK_FILL)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(Glyph.Check, CHECK_INK, 16.dp)
            Text(stringResource(Res.string.discover_sha256_verified), color = CHECK_INK, style = body(TextSize.LABEL, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun Features() {
    AutoGrid(FEATURES.size, FEATURE_WIDTH, 16.dp) { index ->
        val feature = FEATURES[index]
        FeatureCard(feature.title, feature.text, feature.top, feature.bottom, feature.glyph)
    }
}

private class Feature(
    val title: String,
    val text: String,
    val top: Color,
    val bottom: Color,
    val glyph: Glyph,
)

private val FEATURES =
    listOf(
        Feature(
            tr(Res.string.discover_apps_title),
            tr(Res.string.discover_apps_mac),
            Color(0xFF6CB6FF),
            Color(0xFF0873E0),
            Glyph.Window,
        ),
        Feature(tr(Res.string.discover_rollback_title), tr(Res.string.discover_rollback_text), Color(0xFFFFC78A), Color(0xFFF07A12), Glyph.Undo),
        Feature(tr(Res.string.discover_reproducible_title), tr(Res.string.discover_reproducible_text), Color(0xFF7FF0CC), Color(0xFF1FA88A), Glyph.Lock),
    )

private const val HERO_SIZE = 46
private const val HERO_BODY = 16f
private const val ART_TEXT = 12
private const val ART_BACK = 20f
private const val ART_TURN = -30f
private const val ART_SPIN = 3f
private const val ART_DISTANCE = 24f
private const val PLATE_X = 0.06f
private const val PLATE_Y = 0.1f
private const val PLATE_WIDTH = 0.82f
private const val PLATE_HEIGHT = 0.76f
private const val TILE_X = 0.5f
private const val TILE_Y = 0.34f
private const val TAG_X = 0.58f
private const val TAG_Y = 0.16f
private const val LINE_FADE = 0.8f
private val TILE_WIDTH = 270.dp
private val FEATURE_WIDTH = 260.dp
private val HERO_ROOMY = 820.dp
private val ART_HEIGHT = 320.dp
private val HERO_INK = Color(0xDBFFFFFF)
private val PLATE_TOP = Color(0x52FFFFFF)
private val PLATE_BOTTOM = Color(0x14FFFFFF)
private val PLATE_RIM = Color(0x73FFFFFF)
private val ART_TILE_TOP = Color(0xFF11524A)
private val ART_TILE_BOTTOM = Color(0xFF072A26)
private val CHECK_FILL = Color(0xE0FFFFFF)
private val CHECK_INK = Color(0xFF12784A)
