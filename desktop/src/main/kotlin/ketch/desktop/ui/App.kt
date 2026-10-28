// The window's content: the sidebar, the bars that belong to no screen (the lock held, the running operation, an
// error), the core's questions, and the screen the sidebar picked or a package's page over it.
package ketch.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.LoginItem
import ketch.desktop.settings.MemoryKeyValues
import ketch.desktop.settings.Unavailable
import ketch.desktop.store.KetchStore
import ketch.desktop.store.StoreState
import ketch.desktop.store.UpdateChecks
import ketch.desktop.store.UpdateNotifier
import ketch.desktop.ui.screens.ActivityScreen
import ketch.desktop.ui.screens.DiscoverScreen
import ketch.desktop.ui.screens.DoctorScreen
import ketch.desktop.ui.screens.InstalledScreen
import ketch.desktop.ui.screens.PackageScreen
import ketch.desktop.ui.screens.SettingsScreen
import ketch.desktop.ui.screens.UpdatesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

enum class Section(
    val title: String,
    val tag: String,
) {
    Installed("Installed", "installed"),
    Discover("Discover", "discover"),
    Updates("Updates", "updates"),
    Activity("Activity", "activity"),
    Doctor("Doctor", "doctor"),
    Settings("Settings", "settings"),
}

/** Where the window is, so the tray, a notice and the app menu can steer it as the sidebar does. */
class Navigation {
    var section by mutableStateOf(Section.Installed)
        private set
    var open by mutableStateOf<String?>(null)
    var confirmingUpdateAll by mutableStateOf(false)
    var showingAbout by mutableStateOf(false)
    var windowVisible by mutableStateOf(true)

    /** Bumped to bring the window to the front. */
    var windowRequests by mutableStateOf(0)
        private set

    fun show(section: Section) {
        this.section = section
        open = null
    }

    fun bringForward() {
        windowVisible = true
        windowRequests++
    }
}

/** The app's own parts the screens use; the defaults suit a test, which refreshes once instead of on a timer. */
class AppParts(
    val settings: AppSettings = AppSettings(MemoryKeyValues()),
    val notifier: UpdateNotifier = NoNotices,
    val login: LoginItem = Unavailable,
    val periodic: Boolean = false,
)

object NoNotices : UpdateNotifier {
    override suspend fun requestAuthorization() = false

    override fun post(
        title: String,
        body: String,
    ) = Unit
}

/**
 * What the screens may ask of the store. Operations run on the window's scope, not the screen's, so an install goes
 * on when the person moves to another screen.
 */
class Actions(
    val store: KetchStore,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
) {
    fun run(action: suspend KetchStore.() -> Unit) {
        scope.launch { store.action() }
    }

    /** Installs take the prerelease setting, as the macOS app's do. */
    fun install(spec: String) = run { install(spec, settings.state.value.prereleases) }
}

@Composable
fun KetchApp(
    store: KetchStore,
    links: ReceiveChannel<String>? = null,
    navigation: Navigation = remember { Navigation() },
    parts: AppParts = remember { AppParts() },
) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = remember(store, scope) { Actions(store, scope, parts.settings) }
    val checks = remember(store) { UpdateChecks(store, parts.settings, parts.notifier) }

    LaunchedEffect(store) {
        if (parts.periodic) launch { checks.run() } else store.refresh()
        store.runDoctor()
    }
    LaunchedEffect(links) { links?.let { for (link in it) store.open(link) } }
    LaunchedEffect(state.linked) {
        state.linked?.let {
            navigation.open = it
            store.clearLink()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row {
            Sidebar(state, navigation.section, navigation::show)
            Column(Modifier.fillMaxSize()) {
                Bars(state, actions)
                val page = navigation.open
                if (page != null) {
                    PackageScreen(page, state, actions, onBack = { navigation.open = null })
                } else {
                    Screen(state, actions, navigation, parts, checks)
                }
            }
        }
        Questions(state, actions)
        if (navigation.showingAbout) About(store.coreVersion) { navigation.showingAbout = false }
    }
}

@Composable
private fun Screen(
    state: StoreState,
    actions: Actions,
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
) {
    val onOpen = { name: String -> navigation.open = name }
    when (navigation.section) {
        Section.Installed -> InstalledScreen(state, actions, onOpen)
        Section.Discover -> DiscoverScreen(state, actions, onOpen)
        Section.Updates -> UpdatesScreen(state, actions, navigation)
        Section.Activity -> ActivityScreen(state)
        Section.Doctor -> DoctorScreen(state, actions)
        Section.Settings -> SettingsScreen(actions, parts, checks, onAbout = { navigation.showingAbout = true })
    }
}

@Composable
private fun Sidebar(
    state: StoreState,
    selected: Section,
    onSelect: (Section) -> Unit,
) {
    PermanentDrawerSheet(Modifier.width(SIDEBAR_WIDTH.dp)) {
        Text("Ketch", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(LocalSpacing.current.xl))
        for (section in Section.entries) {
            val count =
                when (section) {
                    Section.Updates -> state.pendingUpgradeCount
                    Section.Doctor -> state.problemCount
                    else -> 0
                }
            NavigationDrawerItem(
                label = { Text(section.title) },
                selected = section == selected,
                onClick = { onSelect(section) },
                badge = { if (count > 0) Badge { Text("$count") } },
                modifier =
                    Modifier
                        .padding(horizontal = LocalSpacing.current.sm)
                        .testTag("sidebar-${section.tag}")
                        .semantics { if (count > 0) contentDescription = "${section.title}, ${plural(count, section.counted)}" },
            )
        }
    }
}

private val Section.counted get() = if (this == Section.Updates) "update" else "problem"

fun plural(
    count: Int,
    noun: String,
) = if (count == 1) "1 $noun" else "$count ${noun}s"

private const val SIDEBAR_WIDTH = 220
