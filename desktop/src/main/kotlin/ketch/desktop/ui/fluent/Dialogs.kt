// The app's dialogs in this look: the confirmations, the core's question and About.
package ketch.desktop.ui.fluent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ketch.desktop.ui.ABOUT_LICENCES
import ketch.desktop.ui.ABOUT_LICENCE_TITLE
import ketch.desktop.ui.ABOUT_LINK
import ketch.desktop.ui.ABOUT_REPOSITORY
import ketch.desktop.ui.ABOUT_SUMMARY
import ketch.desktop.ui.appVersion
import ketch.desktop.ui.glass.Mark
import ketch.wire.Question
import pyrlyn.fluent.AppIconSize
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.ContentDialog
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.RadioButtons

@Composable
fun FluentConfirm(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean,
    subject: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ContentDialog(
        title,
        onDismiss,
        footer = {
            FluentButton(confirm, if (destructive) ButtonStyle.DangerFill else ButtonStyle.Accent, Modifier.weight(1f).testTag("confirm-yes"), onClick = onConfirm)
            FluentButton("Cancel", modifier = Modifier.weight(1f), onClick = onDismiss)
        },
        Modifier.testTag("confirm"),
        leading = { subject?.let { PackageIcon(it, AppIconSize.Medium) } },
    ) { Words(message) }
}

@Composable
fun FluentChooseBinary(
    question: Question.ChooseBinary,
    onPick: (Int) -> Unit,
    onDecline: () -> Unit,
) {
    var picked by remember(question) { mutableIntStateOf(0) }
    ContentDialog(
        "Which binary should ${question.pkg} link?",
        onDecline,
        footer = {
            FluentButton("Link", ButtonStyle.Accent, Modifier.weight(1f)) { onPick(picked) }
            FluentButton("Cancel", modifier = Modifier.weight(1f), onClick = onDecline)
        },
        Modifier.testTag("question-choose-binary"),
    ) {
        Words("${question.pkg} ships several programs. ketch links one onto your PATH.")
        RadioButtons(question.candidates.indices.toList(), picked, { question.candidates[it] }) { picked = it }
    }
}

@Composable
fun FluentAbout(
    coreVersion: String,
    onClose: () -> Unit,
) {
    val c = LocalFluent.current
    val type = LocalFluentType.current
    val uri = LocalUriHandler.current
    ContentDialog(
        "Ketch",
        onClose,
        footer = { FluentButton("Close", modifier = Modifier.fillMaxWidth(), onClick = onClose) },
        Modifier.testTag("about"),
        leading = { Mark(MARK_SIZE) },
    ) {
        Text("Version $appVersion · ketch $coreVersion", color = c.ink2, style = type.mono)
        Words(ABOUT_SUMMARY)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ABOUT_LICENCE_TITLE, color = c.ink, style = type.body.copy(fontWeight = FontWeight.SemiBold))
            Words(ABOUT_LICENCES)
        }
        Text(ABOUT_LINK, color = c.accentInk, style = type.body, modifier = Modifier.clickable { uri.openUri(ABOUT_REPOSITORY) })
    }
}

@Composable
private fun Words(text: String) {
    Text(text, color = LocalFluent.current.ink, style = LocalFluentType.current.body)
}

private val MARK_SIZE = 48.dp
