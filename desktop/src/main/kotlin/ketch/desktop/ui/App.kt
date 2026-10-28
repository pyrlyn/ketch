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
import ketch.desktop.store.KetchStore
import ketch.desktop.store.StoreState
import ketch.desktop.ui.screens.ActivityScreen
import ketch.desktop.ui.screens.DiscoverScreen
import ketch.desktop.ui.screens.DoctorScreen
import ketch.desktop.ui.screens.InstalledScreen
import ketch.desktop.ui.screens.PackageScreen
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
}

/**
 * What the screens may ask of the store. Operations run on the window's scope, not the screen's, so an install goes
 * on when the person moves to another screen.
 */
class Actions(
    val store: KetchStore,
    private val scope: CoroutineScope,
) {
    fun run(action: suspend KetchStore.() -> Unit) {
        scope.launch { store.action() }
    }
}

@Composable
fun KetchApp(
    store: KetchStore,
    links: ReceiveChannel<String>? = null,
) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = remember(store, scope) { Actions(store, scope) }
    var section by remember { mutableStateOf(Section.Installed) }
    var open by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        store.refresh()
        store.runDoctor()
    }
    LaunchedEffect(links) { links?.let { for (link in it) store.open(link) } }
    LaunchedEffect(state.linked) {
        state.linked?.let {
            open = it
            store.clearLink()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row {
            Sidebar(state, section) {
                section = it
                open = null
            }
            Column(Modifier.fillMaxSize()) {
                Bars(state, actions)
                val page = open
                if (page != null) {
                    PackageScreen(page, state, actions, onBack = { open = null })
                } else {
                    Screen(section, state, actions, onOpen = { open = it })
                }
            }
        }
        Questions(state, actions)
    }
}

@Composable
private fun Screen(
    section: Section,
    state: StoreState,
    actions: Actions,
    onOpen: (String) -> Unit,
) {
    when (section) {
        Section.Installed -> InstalledScreen(state, actions, onOpen)
        Section.Discover -> DiscoverScreen(state, actions, onOpen)
        Section.Updates -> UpdatesScreen(state, actions)
        Section.Activity -> ActivityScreen(state)
        Section.Doctor -> DoctorScreen(state, actions)
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
