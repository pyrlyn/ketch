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
import ketch.desktop.ui.glass.GlassAbout

/** The app's version: the packaged launcher says it; a development run has none. */
val appVersion: String get() = System.getProperty("jpackage.app-version") ?: "development"

const val ABOUT_SUMMARY = "Installs command-line tools and apps straight from GitHub releases."
const val ABOUT_LICENCE_TITLE = "Licensed under any of, at your choice:"
const val ABOUT_LICENCES = "• GNU GPL-3.0-or-later\n• the ketch Royalty-free License\n• a commercial licence"
const val ABOUT_LINK = "github.com/pyrlyn/ketch"
const val ABOUT_REPOSITORY = "https://$ABOUT_LINK"

@Composable
fun About(
    coreVersion: String,
    onClose: () -> Unit,
) {
    if (LocalLook.current == Look.Glass) {
        GlassAbout(coreVersion, onClose)
        return
    }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("about"),
        title = { Text("Ketch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text("Version $appVersion · ketch $coreVersion", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ABOUT_SUMMARY)
                Text(ABOUT_LICENCE_TITLE, style = MaterialTheme.typography.titleSmall)
                Text(ABOUT_LICENCES)
                TextButton(onClick = { uri.openUri(ABOUT_REPOSITORY) }) { Text(ABOUT_LINK) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
