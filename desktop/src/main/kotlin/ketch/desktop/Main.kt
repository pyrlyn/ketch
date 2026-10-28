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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ketch.desktop.core.CoreException
import ketch.desktop.core.ServeCore
import ketch.desktop.core.locateKetch
import ketch.desktop.store.KetchStore
import ketch.desktop.ui.KetchApp
import ketch.desktop.ui.KetchTheme
import kotlinx.coroutines.channels.Channel
import java.awt.Desktop

fun main(args: Array<String>) {
    // `ketch://` links arrive on the command line on Windows and Linux, and through the open-URI handler on macOS.
    val links = Channel<String>(Channel.UNLIMITED)
    args.filter { it.startsWith("ketch://") }.forEach(links::trySend)
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
        Desktop.getDesktop().setOpenURIHandler { links.trySend(it.uri.toString()) }
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Ketch", state = rememberWindowState(size = DpSize(WIDTH.dp, HEIGHT.dp))) {
            KetchTheme { Started(links) }
        }
    }
}

@Composable
private fun Started(links: Channel<String>) {
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
    if (ready != null) KetchApp(remember(ready) { KetchStore(ready) }, links) else Waiting(failure)
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
