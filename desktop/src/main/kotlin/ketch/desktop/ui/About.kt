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
import ketch.desktop.strings.*
import ketch.desktop.ui.adwaita.AdwaitaAbout
import ketch.desktop.ui.fluent.FluentAbout
import ketch.desktop.ui.glass.GlassAbout
import org.jetbrains.compose.resources.stringResource

/** The app's version: the packaged launcher says it; a development run has none. */
val appVersion: String get() = System.getProperty("jpackage.app-version") ?: "development"

val ABOUT_SUMMARY get() = tr(Res.string.about_summary)
val ABOUT_LICENCE_TITLE get() = tr(Res.string.about_licence_title)
val ABOUT_LICENCES get() = tr(Res.string.about_licences)
const val ABOUT_LINK = "github.com/pyrlyn/ketch"
const val ABOUT_REPOSITORY = "https://$ABOUT_LINK"

@Composable
fun About(
    coreVersion: String,
    onClose: () -> Unit,
) {
    when (LocalLook.current) {
        Look.Glass -> GlassAbout(coreVersion, onClose)
        Look.Fluent -> FluentAbout(coreVersion, onClose)
        Look.Adwaita -> AdwaitaAbout(coreVersion, onClose)
        Look.Material -> MaterialAbout(coreVersion, onClose)
    }
}

@Composable
private fun MaterialAbout(
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
                Text(stringResource(Res.string.about_version, appVersion, coreVersion), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ABOUT_SUMMARY)
                Text(ABOUT_LICENCE_TITLE, style = MaterialTheme.typography.titleSmall)
                Text(ABOUT_LICENCES)
                TextButton(onClick = { uri.openUri(ABOUT_REPOSITORY) }) { Text(ABOUT_LINK) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) } },
    )
}
