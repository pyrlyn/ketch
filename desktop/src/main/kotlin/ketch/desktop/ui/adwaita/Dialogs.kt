package ketch.desktop.ui.adwaita

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
import androidx.compose.ui.unit.dp
import ketch.desktop.ui.ABOUT_LICENCES
import ketch.desktop.ui.ABOUT_LICENCE_TITLE
import ketch.desktop.ui.ABOUT_LINK
import ketch.desktop.ui.ABOUT_REPOSITORY
import ketch.desktop.ui.ABOUT_SUMMARY
import ketch.desktop.ui.appVersion
import ketch.desktop.ui.glass.Mark
import ketch.wire.Question
import pyrlyn.adwaita.AlertDialog
import pyrlyn.adwaita.AvatarSize
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.DialogText
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillStyle
import pyrlyn.adwaita.RadioRow
import pyrlyn.adwaita.RowLine

@Composable
fun AdwaitaConfirm(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean,
    subject: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title,
        onDismiss,
        footer = {
            PillButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
            PillButton(confirm, Modifier.weight(1f).testTag("confirm-yes"), if (destructive) PillStyle.Danger else PillStyle.Accent, onClick = onConfirm)
        },
        Modifier.testTag("confirm"),
        leading = { subject?.let { PackageAvatar(it, AvatarSize.Dialog) } },
    ) { DialogText(message) }
}

@Composable
fun AdwaitaChooseBinary(
    question: Question.ChooseBinary,
    onPick: (Int) -> Unit,
    onDecline: () -> Unit,
) {
    var picked by remember(question) { mutableIntStateOf(0) }
    AlertDialog(
        "Which binary should ${question.pkg} link?",
        onDecline,
        footer = {
            PillButton("Cancel", Modifier.weight(1f), onClick = onDecline)
            PillButton("Link", Modifier.weight(1f), PillStyle.Accent) { onPick(picked) }
        },
        Modifier.testTag("question-choose-binary"),
    ) {
        DialogText("${question.pkg} ships several programs. ketch links one onto your PATH.")
        BoxedList {
            question.candidates.forEachIndexed { index, candidate ->
                if (index > 0) RowLine()
                RadioRow(candidate, index == picked) { picked = index }
            }
        }
    }
}

@Composable
fun AdwaitaAbout(
    coreVersion: String,
    onClose: () -> Unit,
) {
    val c = LocalAdwaita.current
    val type = LocalAdwaitaType.current
    val uri = LocalUriHandler.current
    AlertDialog(
        "Ketch",
        onClose,
        footer = { PillButton("Close", Modifier.fillMaxWidth(), onClick = onClose) },
        Modifier.testTag("about"),
        leading = { Mark(MARK_SIZE) },
    ) {
        Text("Version $appVersion · ketch $coreVersion", color = c.ink2, style = type.mono)
        DialogText(ABOUT_SUMMARY)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ABOUT_LICENCE_TITLE, color = c.ink, style = type.bodyStrong)
            DialogText(ABOUT_LICENCES)
        }
        Text(ABOUT_LINK, color = c.accentText, style = type.body, modifier = Modifier.clickable { uri.openUri(ABOUT_REPOSITORY) })
    }
}

private val MARK_SIZE = 48.dp
