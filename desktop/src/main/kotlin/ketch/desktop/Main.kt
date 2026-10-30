package ketch.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
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
import ketch.desktop.ui.systemLook
import ketch.desktop.ui.trayImage
import ketch.desktop.ui.trayMenu
import ketch.desktop.ui.trayTooltip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Dimension

fun main(args: Array<String>) {
    // `ketch://` links arrive on the command line on Windows and Linux, and through the open-URI handler on macOS.
    val links = Channel<String>(Channel.UNLIMITED)
    args.filter { it.startsWith("ketch://") }.forEach(links::trySend)
    val look = systemLook()
    // Lets the title bar follow the system's light or dark, which the window's content already does.
    if (look == Look.Glass) System.setProperty("apple.awt.application.appearance", "system")
    val navigation = Navigation(if (look == Look.Glass) Section.Discover else Section.Installed)
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
    val parts = remember { AppParts(AppSettings(SystemKeyValues()), TrayNotifier(tray, hasTray), LoginItem.forThisSystem(), periodic = true) }
    if (store != null && hasTray) KetchTray(store, tray, navigation)

    val glass = remember { systemLook() == Look.Glass }
    Window(
        // With a tray the app lives on in it, as the macOS app lives on in the menu bar; Quit is the tray's.
        onCloseRequest = { if (hasTray && store != null) navigation.windowVisible = false else exitApplication() },
        visible = navigation.windowVisible,
        title = "Ketch",
        state = rememberWindowState(size = windowSize(glass)),
        onPreviewKeyEvent = { glass && navigation.findOn(it) },
    ) {
        if (glass) LaunchedEffect(Unit) { contentUnderTitleBar(window) }
        LaunchedEffect(navigation.windowRequests) { if (navigation.windowRequests > 0) window.toFront() }
        KetchTheme {
            if (store != null) KetchApp(store, links, navigation, parts) else Waiting(failure)
        }
    }
}

private fun windowSize(glass: Boolean) = if (glass) DpSize(GLASS_WIDTH.dp, GLASS_HEIGHT.dp) else DpSize(WIDTH.dp, HEIGHT.dp)

/** ⌘K: the window takes it wherever the focus is, as a Mac's search shortcut works. */
internal fun Navigation.findOn(event: KeyEvent): Boolean {
    val asked = event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.K
    if (asked) findSomething()
    return asked
}

/** The look draws to the window's edges, with the real traffic lights over its sidebar. */
private fun contentUnderTitleBar(window: ComposeWindow) {
    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    window.minimumSize = Dimension(GLASS_MIN_WIDTH, GLASS_MIN_HEIGHT)
}

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
            navigation.show(Section.Updates)
            navigation.confirmingUpdateAll = true
            navigation.bringForward()
        }
    }
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
private const val GLASS_WIDTH = 1200
private const val GLASS_HEIGHT = 800
private const val GLASS_MIN_WIDTH = 900
private const val GLASS_MIN_HEIGHT = 600
