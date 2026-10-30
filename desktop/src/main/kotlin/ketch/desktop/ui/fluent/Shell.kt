// The Windows window: the wall behind everything, the title bar this look draws itself, the navigation view, and the
// flyouts that belong to no page. Search lives in the title bar, so its query outlives the page.
package ketch.desktop.ui.fluent

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ketch.desktop.settings.AppSettings
import ketch.desktop.store.Activity
import ketch.desktop.store.StoreState
import ketch.desktop.store.UpdateChecks
import ketch.desktop.ui.About
import ketch.desktop.ui.Actions
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.LIBRARY
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Questions
import ketch.desktop.ui.Section
import ketch.desktop.ui.Source
import ketch.desktop.ui.glass.Mark
import ketch.desktop.ui.glyph
import ketch.desktop.ui.plural
import ketch.desktop.ui.searchFocus
import ketch.wire.Settings
import pyrlyn.fluent.ActivityFlyout
import pyrlyn.fluent.AppIcon
import pyrlyn.fluent.AppIconSize
import pyrlyn.fluent.CaptionButtons
import pyrlyn.fluent.ContentLayer
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.FluentIconButton
import pyrlyn.fluent.FlyoutNotice
import pyrlyn.fluent.IconGradient
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.NavDot
import pyrlyn.fluent.NavIcon
import pyrlyn.fluent.NavItem
import pyrlyn.fluent.NavLabel
import pyrlyn.fluent.ProgressRing
import pyrlyn.fluent.SearchBox
import pyrlyn.fluent.TitleBar
import pyrlyn.fluent.Wall
import pyrlyn.fluent.WindowMaterialLayer
import pyrlyn.fluent.WindowZone
import pyrlyn.fluent.windowZone
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon
import kotlin.math.roundToInt

@Composable
fun FluentApp(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
) {
    var config by remember { mutableStateOf<Settings?>(null) }
    LaunchedEffect(actions.store) { config = actions.store.config() }
    var query by rememberSaveable { mutableStateOf("") }
    // A new query cancels the wait for the one before; the store also shows only the newest answer.
    LaunchedEffect(query) { actions.store.searchAfterTyping(query) }
    KetchFluent(parts.settings) {
        Box(Modifier.fillMaxSize().testTag("fluent")) {
            Wall()
            WindowMaterialLayer()
            Column(Modifier.fillMaxSize()) {
                WindowBar(
                    leading = {
                        FluentIconButton(Glyph.ArrowBack, "Back", Modifier.testTag("back").windowZone(WindowZone.Content), width = BACK_WIDTH, enabled = navigation.canGoBack) { navigation.back() }
                        Brand()
                    },
                    center = {
                        SearchBox(
                            query,
                            "Search owner/repo or name",
                            Modifier
                                .windowZone(WindowZone.Content)
                                .widthIn(max = SEARCH_WIDTH)
                                .fillMaxWidth()
                                .testTag("discover-query"),
                            searchFocus(navigation),
                        ) {
                            query = it
                            if (navigation.section != Section.Discover || navigation.open != null) navigation.show(Section.Discover)
                        }
                    },
                )
                Row(Modifier.fillMaxSize()) {
                    NavigationView(state, navigation)
                    ContentLayer(Modifier.weight(1f)) {
                        Screen(state, actions, navigation, parts, checks, config, query)
                        val inline = navigation.section == Section.Activity && navigation.open == null
                        Column(Modifier.align(Alignment.BottomEnd).padding(FLYOUT_MARGIN).widthIn(max = FLYOUT_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Flyouts(state, actions, navigation, showActivity = !inline)
                        }
                    }
                }
            }
        }
        Questions(state, actions)
        if (navigation.showingAbout) About(actions.store.coreVersion) { navigation.showingAbout = false }
    }
}

/** The window before the core is up: without this title bar it could be neither moved nor closed. */
@Composable
fun FluentWaiting(
    settings: AppSettings,
    failure: String?,
) {
    KetchFluent(settings) {
        Box(Modifier.fillMaxSize()) {
            Wall()
            WindowMaterialLayer()
            Column(Modifier.fillMaxSize()) {
                WindowBar(leading = { Brand() })
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (failure == null) ProgressRing() else Text(failure, color = LocalFluent.current.ink, style = LocalFluentType.current.body)
                }
            }
        }
    }
}

@Composable
private fun WindowBar(
    leading: @Composable () -> Unit,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val chrome = LocalWindowChrome.current
    Box(Modifier.windowZone(WindowZone.Caption).fillMaxWidth().height(TITLE_BAR)) {
        // Behind the controls, which keep their own clicks; the rest of the bar moves the window. The double click wraps
        // the area rather than sitting inside it: a tap detector inside would take the press and the window would never move.
        chrome?.drag(Modifier.fillMaxSize().pointerInput(chrome) { detectTapGestures(onDoubleTap = { chrome.toggleMaximize() }) })
        TitleBar(
            leading = { leading() },
            center = center,
            trailing = { chrome?.let { CaptionButtons(it.isMaximized(), it.minimize, it.toggleMaximize, it.close) } },
        )
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Mark(BRAND_MARK)
        Text("ketch Store", color = LocalFluent.current.ink, style = LocalFluentType.current.caption)
    }
}

@Composable
private fun Screen(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
    config: Settings?,
    query: String,
) {
    val page = navigation.open
    if (page != null) {
        FluentPackage(page, state, actions, navigation)
        return
    }
    when (navigation.section) {
        Section.Discover -> FluentDiscover(state, actions, navigation, query)
        Section.Installed -> FluentInstalled(state, actions, navigation, config)
        Section.Updates -> FluentUpdates(state, actions, navigation)
        Section.Activity -> FluentActivity(state, actions)
        Section.Doctor -> FluentDoctor(state, actions)
        Section.Settings -> FluentSettings(navigation, parts, checks, config)
    }
}

@Composable
private fun NavigationView(
    state: StoreState,
    navigation: Navigation,
) {
    val c = LocalFluent.current
    Column(
        Modifier.width(NAV_WIDTH).fillMaxHeight().padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (section in LIBRARY) SectionItem(section, state, navigation)
        NavLabel("Sources")
        for (source in Source.entries) {
            val color = if (source == Source.Releases) c.success else c.accent
            NavItem(source.title, navigation.source == source, Modifier.testTag("sidebar-${source.tag}"), leading = { NavDot(color) }) { navigation.show(source) }
        }
        Spacer(Modifier.weight(1f))
        SectionItem(Section.Settings, state, navigation)
    }
}

@Composable
private fun SectionItem(
    section: Section,
    state: StoreState,
    navigation: Navigation,
) {
    val selected = navigation.section == section && navigation.source == null
    val (count, noun) =
        when (section) {
            Section.Installed -> state.installed.size to "package"
            Section.Updates -> state.pendingUpgradeCount to "update"
            Section.Doctor -> state.problemCount to "problem"
            else -> 0 to ""
        }
    NavItem(
        section.title,
        selected,
        Modifier.testTag("sidebar-${section.tag}").semantics { if (count > 0) contentDescription = "${section.title}, ${plural(count, noun)}" },
        badge = count.takeIf { it > 0 },
        badgeUpdate = section != Section.Installed,
        leading = { NavIcon(section.glyph, selected) },
    ) { navigation.show(section) }
}

/** The lock held, the running operation and an error the person has not dismissed. */
@Composable
private fun Flyouts(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    showActivity: Boolean,
) {
    val c = LocalFluent.current
    state.busy?.let { busy ->
        FlyoutNotice("ketch is busy", busy.message, Modifier.testTag("busy-bar"), leading = { GlyphIcon(Glyph.Lock, c.busy, GLYPH) }) {
            FluentButton("Retry") { actions.run { retryBusy() } }
        }
    }
    if (showActivity) OperationFlyout(state) { FluentButton("Details") { navigation.show(Section.Activity) } }
    state.error?.let { error ->
        FlyoutNotice("Something went wrong", error, Modifier.testTag("error-bar"), leading = { GlyphIcon(Glyph.Warning, c.danger, GLYPH) }) {
            FluentButton("Dismiss") { actions.store.dismissError() }
        }
    }
}

/** The running operation, if there is one. */
@Composable
fun OperationFlyout(
    state: StoreState,
    action: @Composable (Activity) -> Unit,
) {
    val activity = state.activity ?: return
    ActivityFlyout(activity.title, Modifier.testTag("activity-bar"), activity.status, (activity.fraction * PERCENT).roundToInt()) { action(activity) }
}

/** One screen, scrolling by itself. The bottom keeps the last row clear of the flyout. */
@Composable
fun FluentPage(
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PageContent)
            .testTag("page-$tag"),
        verticalArrangement = Arrangement.spacedBy(PageGap),
        content = content,
    )
}

val PageContent = PaddingValues(start = 36.dp, top = 28.dp, end = 36.dp, bottom = 132.dp)
val PageGap = 24.dp

/** A package's icon, which has no artwork: its first letters on a colour its name picks. */
@Composable
fun PackageIcon(
    name: String,
    size: AppIconSize,
    raised: Boolean = false,
) = AppIcon(name.take(2), IconGradient.of(name), size, raised = raised)

private const val PERCENT = 100
private val TITLE_BAR = 48.dp
private val BACK_WIDTH = 40.dp
private val SEARCH_WIDTH = 520.dp
private val NAV_WIDTH = 236.dp
private val FLYOUT_MARGIN = 24.dp
private val FLYOUT_WIDTH = 540.dp
private val BRAND_MARK = 18.dp
private val GLYPH = 22.dp
