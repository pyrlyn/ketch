package ketch.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag

/** The app's version: the packaged launcher says it; a development run has none. */
val appVersion: String get() = System.getProperty("jpackage.app-version") ?: "development"

@Composable
fun About(
    coreVersion: String,
    onClose: () -> Unit,
) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("about"),
        title = { Text("Ketch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text("Version $appVersion · ketch $coreVersion", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Installs command-line tools and apps straight from GitHub releases.")
                Text("Licensed under any of, at your choice:", style = MaterialTheme.typography.titleSmall)
                Text("• GNU GPL-3.0-or-later\n• the ketch Royalty-free License\n• a commercial licence")
                TextButton(onClick = { uri.openUri(REPOSITORY) }) { Text("github.com/pyrlyn/ketch") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private const val REPOSITORY = "https://github.com/pyrlyn/ketch"
