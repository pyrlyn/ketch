// The Linux window: the wall, the header bar this look draws itself with its view switcher and menu, and the toasts that
// belong to no page. Search sits in a bar under the header, so its query outlives the page.
package ketch.desktop.ui.adwaita

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ketch.desktop.store.StoreState
import ketch.desktop.store.UpdateChecks
import ketch.desktop.strings.*
import ketch.desktop.ui.About
import ketch.desktop.ui.Actions
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Questions
import ketch.desktop.ui.Section
import ketch.desktop.ui.Source
import ketch.desktop.ui.fluent.LocalWindowChrome
import ketch.desktop.ui.glyph
import ketch.desktop.ui.searchFocus
import ketch.wire.Settings
import org.jetbrains.compose.resources.stringResource
import pyrlyn.adwaita.AppAvatar
import pyrlyn.adwaita.AvatarSize
import pyrlyn.adwaita.Clamp
import pyrlyn.adwaita.Corners
import pyrlyn.adwaita.Entry
import pyrlyn.adwaita.FlatButton
import pyrlyn.adwaita.GlowDot
import pyrlyn.adwaita.HeaderBar
import pyrlyn.adwaita.IconGradient
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.MenuItem
import pyrlyn.adwaita.MenuLabel
import pyrlyn.adwaita.MenuSeparator
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.PopoverMenu
import pyrlyn.adwaita.Spinner
import pyrlyn.adwaita.SwitcherItem
import pyrlyn.adwaita.Toast
import pyrlyn.adwaita.ViewSwitcher
import pyrlyn.adwaita.Wall
import pyrlyn.adwaita.WindowControls
import pyrlyn.adwaita.WindowGlass
import pyrlyn.glass.Glyph
import pyrlyn.glass.GlyphIcon

@Composable
fun AdwaitaApp(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
) {
    var config by remember { mutableStateOf<Settings?>(null) }
    LaunchedEffect(actions.store) { config = actions.store.config() }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // A new query cancels the wait for the one before; the store also shows only the newest answer.
    LaunchedEffect(query) { actions.store.searchAfterTyping(query) }
    // The shortcut asks from outside the bar, which has to be there to take the focus.
    LaunchedEffect(navigation.wantsSearch) { if (navigation.wantsSearch) searching = true }
    val closeSearch = {
        searching = false
        query = ""
    }
    KetchAdwaita {
        AdwaitaWindow(Modifier.testTag("adwaita")) {
            Header(state, navigation, searching) {
                if (searching) {
                    closeSearch()
                } else {
                    searching = true
                    navigation.wantsSearch = true
                }
            }
            if (searching) {
                SearchBar(query, navigation, closeSearch) {
                    query = it
                    if (navigation.section != Section.Discover || navigation.open != null) navigation.show(Section.Discover)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Screen(state, actions, navigation, parts, checks, config, query)
                val inline = navigation.section == Section.Activity && navigation.open == null
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(TOAST_MARGIN).widthIn(max = TOAST_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Toasts(state, actions, navigation, showActivity = !inline)
                }
            }
        }
        Questions(state, actions)
        if (navigation.showingAbout) About(actions.store.coreVersion) { navigation.showingAbout = false }
    }
}

/** The window before the core is up: without this header it could be neither moved nor closed. */
@Composable
fun AdwaitaWaiting(failure: String?) {
    KetchAdwaita {
        AdwaitaWindow {
            WindowHeader()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (failure == null) {
                    Spinner(LocalAdwaita.current.accent)
                } else {
                    Text(failure, color = LocalAdwaita.current.ink, style = LocalAdwaitaType.current.body)
                }
            }
        }
    }
}

/** The wall and the glass, cut to the window's corners where the desktop can show through them. */
@Composable
private fun AdwaitaWindow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = LocalWindowChrome.current
    val shape = if (chrome?.rounded?.invoke() == true) Corners.Window else RectangleShape
    Box(modifier.fillMaxSize().clip(shape)) {
        Wall()
        WindowGlass()
        Column(Modifier.fillMaxSize(), content = content)
        chrome?.edges?.invoke()
    }
}

@Composable
private fun Header(
    state: StoreState,
    navigation: Navigation,
    searching: Boolean,
    onSearch: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    WindowHeader(
        start = {
            if (navigation.open != null) {
                FlatButton(Glyph.ArrowBack, stringResource(Res.string.action_back), Modifier.testTag("back")) { navigation.back() }
            }
            FlatButton(Glyph.Search, stringResource(Res.string.action_search), Modifier.testTag("search"), on = searching, onClick = onSearch)
        },
        center = {
            ViewSwitcher(switcherItems(state), navigation.section.takeIf { it in SWITCHED && navigation.source == null }) { navigation.show(it) }
        },
        menu = {
            Box {
                FlatButton(Glyph.Menu, stringResource(Res.string.window_main_menu), Modifier.testTag("menu"), on = menu) { menu = true }
                if (menu) MainMenu(state, navigation) { menu = false }
            }
        },
    )
}

private fun switcherItems(state: StoreState) =
    SWITCHED.map { section ->
        val count = if (section == Section.Updates) state.pendingUpgradeCount else 0
        SwitcherItem(
            section,
            section.label,
            section.glyph,
            badge = count.takeIf { it > 0 }?.toString(),
            modifier =
                Modifier.testTag("sidebar-${section.tag}").semantics {
                    if (count > 0) contentDescription = "${section.label}, ${plural(count, Res.plurals.updates)}"
                },
        )
    }

/** The header and, behind what it holds, the area that moves the window. */
@Composable
private fun WindowHeader(
    start: @Composable () -> Unit = {},
    center: @Composable () -> Unit = {},
    menu: @Composable () -> Unit = {},
) {
    val chrome = LocalWindowChrome.current
    Box(Modifier.fillMaxWidth()) {
        // The double click wraps the area rather than sitting inside it: a tap detector inside would take the press and
        // the window would never move.
        chrome?.drag(Modifier.matchParentSize().pointerInput(chrome) { detectTapGestures(onDoubleTap = { chrome.toggleMaximize() }) })
        HeaderBar(
            start = { start() },
            center = center,
            end = {
                menu()
                chrome?.let { WindowControls(it.isMaximized(), Modifier.padding(start = 8.dp), it.minimize, it.toggleMaximize, it.close) }
            },
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    navigation: Navigation,
    onClose: () -> Unit,
    onChange: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        Entry(
            query,
            stringResource(Res.string.discover_search_field),
            Modifier
                .widthIn(max = SEARCH_WIDTH)
                .fillMaxWidth()
                .testTag("discover-query")
                .onPreviewKeyEvent {
                    val closing = it.key == Key.Escape
                    if (closing) onClose()
                    closing
                },
            Glyph.Search,
            searchFocus(navigation),
            onChange,
        )
    }
}

@Composable
private fun MainMenu(
    state: StoreState,
    navigation: Navigation,
    onDismiss: () -> Unit,
) {
    val c = LocalAdwaita.current
    val pick = { action: () -> Unit ->
        onDismiss()
        action()
    }
    PopoverMenu(onDismiss, Modifier.testTag("main-menu")) {
        MenuItem(Section.Activity.title, Modifier.testTag("sidebar-${Section.Activity.tag}"), Section.Activity.glyph) { pick { navigation.show(Section.Activity) } }
        val problems = state.problemCount.takeIf { it > 0 }?.let { plural(it, Res.plurals.problems) }
        MenuItem(Section.Doctor.title, Modifier.testTag("sidebar-${Section.Doctor.tag}"), Section.Doctor.glyph, shortcut = problems) { pick { navigation.show(Section.Doctor) } }
        MenuSeparator()
        MenuLabel(stringResource(Res.string.section_sources))
        for (source in Source.entries) {
            val color = if (source == Source.Releases) c.accent else c.info
            MenuItem(source.title, Modifier.testTag("sidebar-${source.tag}"), leading = { GlowDot(color) }) { pick { navigation.show(source) } }
        }
        MenuSeparator()
        MenuItem(Section.Settings.title, Modifier.testTag("sidebar-${Section.Settings.tag}"), Section.Settings.glyph, shortcut = "Ctrl+,") {
            pick { navigation.show(Section.Settings) }
        }
        MenuItem(stringResource(Res.string.window_about_store), Modifier.testTag("menu-about"), Glyph.Info) { pick { navigation.showingAbout = true } }
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
        AdwaitaPackage(page, state, actions, navigation)
        return
    }
    when (navigation.section) {
        Section.Discover -> AdwaitaDiscover(state, actions, navigation, config, query)
        Section.Installed -> AdwaitaInstalled(state, actions, navigation, config)
        Section.Updates -> AdwaitaUpdates(state, actions, navigation)
        Section.Activity -> AdwaitaActivity(state, actions)
        Section.Doctor -> AdwaitaDoctor(state, actions, config)
        Section.Settings -> AdwaitaSettings(parts, checks, config)
    }
}

/** The lock held, the running operation and an error the person has not dismissed. */
@Composable
private fun Toasts(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    showActivity: Boolean,
) {
    val c = LocalAdwaita.current
    state.busy?.let { busy ->
        Notice(busy.message, Modifier.testTag("busy-bar"), { GlyphIcon(Glyph.Lock, c.warning, TOAST_GLYPH) }, stringResource(Res.string.action_retry)) { actions.run { retryBusy() } }
    }
    state.activity?.takeIf { showActivity }?.let { activity ->
        Notice(activity.title, Modifier.testTag("activity-bar"), { Spinner(c.accent) }, stringResource(Res.string.action_details)) { navigation.show(Section.Activity) }
    }
    state.error?.let { error ->
        Notice(error, Modifier.testTag("error-bar"), { GlyphIcon(Glyph.Warning, c.dangerText, TOAST_GLYPH) }, stringResource(Res.string.action_dismiss)) { actions.store.dismissError() }
    }
}

@Composable
private fun Notice(
    text: String,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    action: String,
    onAction: () -> Unit,
) {
    Toast(modifier, action = { PillButton(action, style = PillStyle.Flat, size = PillSize.Small, onClick = onAction) }) {
        leading()
        Text(text, color = LocalAdwaita.current.ink, style = LocalAdwaitaType.current.body, modifier = Modifier.weight(1f, fill = false))
    }
}

/** One screen, scrolling by itself. */
@Composable
fun AdwaitaPage(
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) = Clamp(Modifier.testTag("page-$tag"), content = content)

/** A package's icon, which has no artwork: its first letters on a colour its name picks. */
@Composable
fun PackageAvatar(
    name: String,
    size: AvatarSize = AvatarSize.Small,
) = AppAvatar(name.take(2), IconGradient.of(name), size)

/** GNOME's words for what the other looks call Discover. */
val Section.label get() = if (this == Section.Discover) tr(Res.string.section_explore) else title

private val SWITCHED = listOf(Section.Discover, Section.Installed, Section.Updates)
private val TOAST_MARGIN = 24.dp
private val TOAST_WIDTH = 540.dp
private val TOAST_GLYPH = 18.dp
private val SEARCH_WIDTH = 520.dp
