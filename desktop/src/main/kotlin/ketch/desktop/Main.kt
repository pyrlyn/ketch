package ketch.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import ketch.desktop.core.CoreException
import ketch.desktop.core.ServeCore
import ketch.desktop.core.locateKetch
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.LoginItem
import ketch.desktop.settings.SystemKeyValues
import ketch.desktop.store.KetchStore
import ketch.desktop.store.UpdateNotifier
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.KetchApp
import ketch.desktop.ui.KetchTheme
import ketch.desktop.ui.Look
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Section
import ketch.desktop.ui.Tokens
import ketch.desktop.ui.TrayAction
import ketch.desktop.ui.TrayEntry
import ketch.desktop.ui.adwaita.AdwaitaWaiting
import ketch.desktop.ui.fluent.FluentWaiting
import ketch.desktop.ui.fluent.LocalWindowChrome
import ketch.desktop.ui.fluent.WindowChrome
import ketch.desktop.ui.systemLook
import ketch.desktop.ui.trayImage
import ketch.desktop.ui.trayMenu
import ketch.desktop.ui.trayTooltip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import pyrlyn.fluent.LocalWindowZones
import pyrlyn.fluent.WindowZones
import pyrlyn.fluent.WindowsFrame
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    // `ketch://` links arrive on the command line on Windows and Linux, and through the open-URI handler on macOS.
    val links = Channel<String>(Channel.UNLIMITED)
    args.filter { it.startsWith("ketch://") }.forEach(links::trySend)
    val look = systemLook()
    // Lets the title bar follow the system's light or dark, which the window's content already does.
    if (look == Look.Glass) System.setProperty("apple.awt.application.appearance", "system")
    val navigation = Navigation(if (look == Look.Material) Section.Installed else Section.Discover)
    if (Desktop.isDesktopSupported()) systemMenus(Desktop.getDesktop(), links, navigation)
    application { Ketch(links, navigation) }
}

/** The macOS app menu's About and Settings, and links the system opens the app for. */
private fun systemMenus(
    desktop: Desktop,
    links: Channel<String>,
    navigation: Navigation,
) {
    if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { links.trySend(it.uri.toString()) }
    if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
        desktop.setAboutHandler {
            navigation.showingAbout = true
            navigation.bringForward()
        }
    }
    if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
        desktop.setPreferencesHandler {
            navigation.show(Section.Settings)
            navigation.bringForward()
        }
    }
}

@Composable
private fun ApplicationScope.Ketch(
    links: Channel<String>,
    navigation: Navigation,
) {
    var core by remember { mutableStateOf<ServeCore?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val exe = locateKetch()
        if (exe == null) {
            failure = "The ketch program is missing from the app. Set KETCH_EXE to a ketch to run."
            return@LaunchedEffect
        }
        try {
            core = ServeCore.start(exe)
        } catch (e: CoreException) {
            failure = e.message
        }
    }
    val ready = core
    // Captured: the effect that ends when the core arrives must close the core it saw (none), not the new one.
    DisposableEffect(ready) { onDispose { ready?.close() } }
    val store = remember(ready) { ready?.let(::KetchStore) }
    val tray = rememberTrayState()
    val hasTray = remember { isTraySupported }
    val parts = remember { AppParts(AppSettings(SystemKeyValues()), notifier(TrayNotifier(tray, hasTray), navigation), LoginItem.forThisSystem(), periodic = true) }
    if (store != null && hasTray) KetchTray(store, tray, navigation)

    val look = remember { systemLook() }
    val translucent = remember { desktopShowsThrough() }
    val windowState = rememberWindowState(size = windowSize(look))
    // With a tray the app lives on in it, as the macOS app lives on in the menu bar; Quit is the tray's.
    val close = { if (hasTray && store != null) navigation.windowVisible = false else exitApplication() }
    Window(
        onCloseRequest = close,
        visible = navigation.windowVisible,
        title = "Ketch",
        state = windowState,
        undecorated = look.drawsItsFrame,
        transparent = look.seeThrough(translucent),
        onPreviewKeyEvent = { navigation.shortcutOn(it, look) },
    ) {
        val zones = remember { WindowZones() }
        // A window composes while AWT attaches it: its native windows exist by now, and it is not on the screen yet.
        val frame = remember { if (look == Look.Fluent) WindowsFrame.attach(window, zones) else null }
        LaunchedEffect(Unit) { dress(window, look, frame) }
        LaunchedEffect(navigation.windowRequests) { if (navigation.windowRequests > 0) window.toFront() }
        val chrome = remember(store) { ownFrame(windowState, frame, close, look == Look.Adwaita, translucent) }
        CompositionLocalProvider(
            LocalWindowChrome provides chrome.takeIf { look.drawsItsFrame },
            LocalWindowZones provides zones.takeIf { frame != null },
        ) {
            KetchTheme(look = look) {
                if (store != null) KetchApp(store, links, navigation, parts) else Starting(look, parts.settings, failure)
            }
        }
    }
}

@Composable
private fun Starting(
    look: Look,
    settings: AppSettings,
    failure: String?,
) = when (look) {
    Look.Fluent -> FluentWaiting(settings, failure)
    Look.Adwaita -> AdwaitaWaiting(failure)
    else -> Waiting(failure)
}

/** The looks that draw their own title bar; on Windows the system's frame still sits around it. */
private val Look.drawsItsFrame get() = this == Look.Fluent || this == Look.Adwaita

private fun Look.seeThrough(translucent: Boolean) = (this == Look.Fluent && WindowsFrame.available) || (this == Look.Adwaita && translucent)

/** The window's own shortcuts; Material's has none. */
private fun Navigation.shortcutOn(
    event: KeyEvent,
    look: Look,
) = look != Look.Material && (findOn(event, look) || settingsOn(event, look))

/** Rounded corners need a compositor behind the window; without one the cut-off corners would come out black. */
private fun desktopShowsThrough() =
    !GraphicsEnvironment.isHeadless() &&
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)

private fun windowSize(look: Look) = if (look == Look.Material) DpSize(WIDTH.dp, HEIGHT.dp) else DpSize(LOOK_WIDTH.dp, LOOK_HEIGHT.dp)

/** ⌘K on a Mac and Ctrl+K elsewhere: the window takes it wherever the focus is, as a system's search shortcut works. */
internal fun Navigation.findOn(
    event: KeyEvent,
    look: Look = Look.Glass,
): Boolean {
    val modifier = if (look == Look.Glass) event.isMetaPressed else event.isCtrlPressed
    val asked = event.type == KeyEventType.KeyDown && modifier && event.key == Key.K
    if (asked) findSomething()
    return asked
}

/** Ctrl+, opens Settings where the look's menu names that shortcut. */
internal fun Navigation.settingsOn(
    event: KeyEvent,
    look: Look,
): Boolean {
    val asked = look == Look.Adwaita && event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Comma
    if (asked) show(Section.Settings)
    return asked
}

private fun dress(
    window: ComposeWindow,
    look: Look,
    frame: WindowsFrame?,
) {
    if (look == Look.Material) return
    if (look == Look.Glass) contentUnderTitleBar(window)
    if (look.drawsItsFrame && frame == null) stayClearOfTheTaskbar(window)
    window.minimumSize = Dimension(LOOK_MIN_WIDTH, LOOK_MIN_HEIGHT)
}

/** The look draws to the window's edges, with the real traffic lights over its sidebar. */
private fun contentUnderTitleBar(window: ComposeWindow) {
    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
}

/** Without the system's frame a window maximizes over the whole screen; the taskbar's room has to be asked for. */
private fun stayClearOfTheTaskbar(window: ComposeWindow) {
    window.maximizedBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
}

/** What the look's own title bar does in place of the system's frame. */
private fun FrameWindowScope.ownFrame(
    state: WindowState,
    frame: WindowsFrame?,
    close: () -> Unit,
    pulledByItsEdges: Boolean,
    translucent: Boolean,
) = WindowChrome(
    isMaximized = { state.placement == WindowPlacement.Maximized },
    minimize = { state.isMinimized = true },
    toggleMaximize = { state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized },
    close = close,
    drag = { modifier -> WindowDraggableArea(modifier) },
    wear = { material, dark -> frame?.wear(material, dark) == true },
    rounded = { translucent && state.placement == WindowPlacement.Floating },
    edges = { if (pulledByItsEdges && state.placement == WindowPlacement.Floating) ResizeEdges(window) },
)

@Composable
private fun ApplicationScope.KetchTray(
    store: KetchStore,
    tray: TrayState,
    navigation: Navigation,
) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = remember { java.awt.Color(Tokens.brand.color("accent.default", Appearance.Light).toArgb()) }
    val icon = remember(state.pendingUpgradeCount) { BitmapPainter(trayImage(state.pendingUpgradeCount, accent).toComposeImageBitmap()) }
    Tray(icon = icon, state = tray, tooltip = trayTooltip(state), onAction = navigation::bringForward) {
        for (entry in trayMenu(state)) {
            when (entry) {
                TrayEntry.Separator -> Separator()
                is TrayEntry.Item -> Item(entry.label, enabled = entry.enabled) { entry.action?.let { act(it, store, navigation, scope) } }
            }
        }
    }
}

private fun ApplicationScope.act(
    action: TrayAction,
    store: KetchStore,
    navigation: Navigation,
    scope: CoroutineScope,
) {
    when (action) {
        is TrayAction.Update -> {
            scope.launch { store.upgrade(listOf(action.name)) }
        }

        TrayAction.Cancel -> {
            store.cancel()
        }

        TrayAction.Check -> {
            scope.launch { store.refresh() }
        }

        TrayAction.Quit -> {
            exitApplication()
        }

        TrayAction.Open -> {
            navigation.bringForward()
        }

        TrayAction.Settings -> {
            navigation.show(Section.Settings)
            navigation.bringForward()
        }

        TrayAction.UpdateAll -> {
            navigation.askToUpdateAll()
        }
    }
}

/** Windows can put an Update button on a notice; a click on one comes from outside Swing's thread. */
private fun notifier(
    tray: UpdateNotifier,
    navigation: Navigation,
) = if (System.getProperty("os.name").startsWith("Windows")) {
    WindowsToast(tray) { update -> SwingUtilities.invokeLater { if (update) navigation.askToUpdateAll() else navigation.bringForward() } }
} else {
    tray
}

/** Notices through the tray icon; a system without a tray has nowhere to show them. */
private class TrayNotifier(
    private val tray: TrayState,
    private val hasTray: Boolean,
) : UpdateNotifier {
    override suspend fun requestAuthorization() = hasTray

    override fun post(
        title: String,
        body: String,
    ) = tray.sendNotification(Notification(title, body, Notification.Type.Info))
}

@Composable
private fun Waiting(failure: String?) {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            if (failure == null) CircularProgressIndicator() else Text(failure, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private const val WIDTH = 1080
private const val HEIGHT = 720
private const val LOOK_WIDTH = 1200
private const val LOOK_HEIGHT = 800
private const val LOOK_MIN_WIDTH = 900
private const val LOOK_MIN_HEIGHT = 600
