// The macOS window: the wallpaper, the floating sidebar, the toolbar each page shares, and the bars that belong to no
// screen. The real traffic lights sit on the sidebar and the title bar's strip stays free, because the window system
// takes every click there.
package ketch.desktop.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.store.UpdateChecks
import ketch.desktop.ui.About
import ketch.desktop.ui.Actions
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Questions
import ketch.desktop.ui.Section
import ketch.desktop.ui.Source
import ketch.desktop.ui.plural
import ketch.wire.Settings
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.CountBadge
import pyrlyn.glass.GlassButton
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.NavigationArrows
import pyrlyn.glass.Pane
import pyrlyn.glass.SidebarItem
import pyrlyn.glass.SidebarLabel
import pyrlyn.glass.Spinner
import pyrlyn.glass.StatusBar
import pyrlyn.glass.Toolbar
import pyrlyn.glass.Wallpaper
import pyrlyn.glass.body
import pyrlyn.glass.glass
import pyrlyn.glass.mono

@Composable
fun GlassApp(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
) {
    var config by remember { mutableStateOf<Settings?>(null) }
    LaunchedEffect(actions.store) { config = actions.store.config() }
    KetchGlass(parts.settings) {
        Box(Modifier.fillMaxSize().testTag("glass")) {
            Wallpaper()
            Row(Modifier.fillMaxSize()) {
                Sidebar(state, navigation)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Screen(state, actions, navigation, parts, checks, config)
                    val inline = navigation.section == Section.Activity && navigation.open == null
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBars(state, actions, showActivity = !inline, Modifier.widthIn(max = STATUS_WIDTH))
                    }
                }
            }
        }
        Questions(state, actions)
        if (navigation.showingAbout) About(actions.store.coreVersion) { navigation.showingAbout = false }
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
) {
    val page = navigation.open
    if (page != null) {
        GlassPackage(page, state, actions, navigation)
        return
    }
    when (navigation.section) {
        Section.Discover -> GlassDiscover(state, actions, navigation)
        Section.Installed -> GlassInstalled(state, actions, navigation, config)
        Section.Updates -> GlassUpdates(state, actions, navigation)
        Section.Activity -> GlassActivity(state, actions, navigation)
        Section.Doctor -> GlassDoctor(state, actions, navigation)
        Section.Settings -> GlassSettings(navigation, parts, checks, config)
    }
}

/** The order of the design's sidebar; Settings stands apart at the foot. */
private val LIBRARY = listOf(Section.Discover, Section.Installed, Section.Updates, Section.Activity, Section.Doctor)

private val Section.glyph
    get() =
        when (this) {
            Section.Discover -> Glyph.Compass
            Section.Installed -> Glyph.Box
            Section.Updates -> Glyph.Refresh
            Section.Activity -> Glyph.Pulse
            Section.Doctor -> Glyph.Shield
            Section.Settings -> Glyph.Gear
        }

@Composable
private fun Sidebar(
    state: StoreState,
    navigation: Navigation,
) {
    val glass = LocalGlass.current
    Column(
        Modifier
            .padding(SIDEBAR_MARGIN)
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .glass(Pane.Sidebar)
            .padding(start = 10.dp, end = 10.dp, top = TRAFFIC_LIGHTS, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Mark(MARK_SIZE)
            Text("ketch", color = glass.ink, style = mono(BRAND, FontWeight.SemiBold))
        }
        for (section in LIBRARY) SectionItem(section, state, navigation)
        SidebarLabel("Sources")
        for (source in Source.entries) {
            SidebarItem(source.title, navigation.source == source, Modifier.testTag("sidebar-${source.tag}"), leading = { Dot(source) }) { navigation.show(source) }
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
    val glass = LocalGlass.current
    val selected = navigation.section == section && navigation.source == null
    val (count, noun) =
        when (section) {
            Section.Updates -> state.pendingUpgradeCount to "update"
            Section.Doctor -> state.problemCount to "problem"
            else -> 0 to ""
        }
    SidebarItem(
        section.title,
        selected,
        Modifier.testTag("sidebar-${section.tag}"),
        description = if (count > 0) "${section.title}, ${plural(count, noun)}" else null,
        leading = { GlyphIcon(section.glyph, if (selected) glass.accent else glass.accentInk) },
        trailing = {
            if (count > 0) CountBadge(count)
            if (section == Section.Installed && state.installed.isNotEmpty()) Text("${state.installed.size}", color = glass.ink2, style = body(COUNT))
        },
    ) { navigation.show(section) }
}

@Composable
private fun Dot(source: Source) {
    val color = if (source == Source.Releases) LocalGlass.current.success else LocalGlass.current.accent
    Box(
        Modifier
            .size(DOT)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** A page's room below the toolbar: the bottom keeps the last row clear of the floating bar. */
val PageContent = PaddingValues(start = 18.dp, end = 28.dp, top = 14.dp, bottom = 120.dp)
val PageGap = 28.dp

/** One screen: the arrows, the title, its own controls on the right, and its content, which scrolls by itself. */
@Composable
fun GlassPage(
    title: String,
    tag: String,
    navigation: Navigation,
    crumb: String? = null,
    toolbar: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = TITLE_BAR).testTag("page-$tag")) {
        Toolbar(
            title,
            Modifier.padding(start = 18.dp, end = 28.dp),
            crumb,
            leading = { NavigationArrows(navigation.canGoBack, navigation.canGoForward, "Back", "Forward", navigation::back, navigation::forward) },
            trailing = toolbar,
        )
        content()
    }
}

/** A page that is one scrolling column. */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PageContent),
        verticalArrangement = Arrangement.spacedBy(PageGap),
        content = content,
    )
}

/** The search field's focus, taken when the person asks for search with ⌘K. */
@Composable
fun searchFocus(navigation: Navigation): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(navigation.wantsSearch) {
        if (navigation.wantsSearch) {
            focus.requestFocus()
            navigation.wantsSearch = false
        }
    }
    return focus
}

/** The lock held, the running operation and an error the person has not dismissed. */
@Composable
fun StatusBars(
    state: StoreState,
    actions: Actions,
    showActivity: Boolean,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current
    state.busy?.let { busy ->
        StatusBar("ketch is busy", busy.message, null, modifier.testTag("busy-bar"), leading = { GlyphIcon(Glyph.Lock, glass.progress, GLYPH) }) {
            GlassButton("Retry", ButtonKind.Secondary) { actions.run { retryBusy() } }
        }
    }
    if (showActivity) ActivityBar(state, actions, modifier)
    state.error?.let { error ->
        StatusBar("Something went wrong", error, null, modifier.testTag("error-bar"), leading = { GlyphIcon(Glyph.Warning, glass.danger, GLYPH) }) {
            GlassButton("Dismiss", ButtonKind.Secondary) { actions.store.dismissError() }
        }
    }
}

@Composable
fun ActivityBar(
    state: StoreState,
    actions: Actions,
    modifier: Modifier = Modifier,
) {
    val activity = state.activity ?: return
    StatusBar(activity.title, activity.status, activity.fraction.toFloat(), modifier.testTag("activity-bar"), leading = { Spinner() }) {
        GlassButton("Cancel", ButtonKind.Secondary, enabled = !activity.isCancelling) { actions.store.cancel() }
    }
}

private const val BRAND = 17
private const val COUNT = 12f
private val SIDEBAR_MARGIN = 10.dp
private val SIDEBAR_WIDTH = 232.dp
private val STATUS_WIDTH = 560.dp

/** The sidebar's first row clears the real traffic lights. */
private val TRAFFIC_LIGHTS = 34.dp

/** The toolbar's controls start under the title bar's strip, which takes every click. */
private val TITLE_BAR = 20.dp
private val MARK_SIZE = 30.dp
private val DOT = 18.dp
private val GLYPH = 22.dp
