// The app's sheets: the confirmations, the core's question and About.
package ketch.desktop.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ketch.desktop.strings.*
import ketch.desktop.ui.ABOUT_LICENCES
import ketch.desktop.ui.ABOUT_LICENCE_TITLE
import ketch.desktop.ui.ABOUT_LINK
import ketch.desktop.ui.ABOUT_REPOSITORY
import ketch.desktop.ui.ABOUT_SUMMARY
import ketch.desktop.ui.appVersion
import ketch.wire.Question
import org.jetbrains.compose.resources.stringResource
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.GlassButton
import pyrlyn.glass.IconSize
import pyrlyn.glass.LetterIcon
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.Sheet
import pyrlyn.glass.SheetActions
import pyrlyn.glass.SheetText
import pyrlyn.glass.SheetTitle
import pyrlyn.glass.TextSize
import pyrlyn.glass.body
import pyrlyn.glass.mono

@Composable
fun GlassConfirm(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean,
    subject: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss, Modifier.testTag("confirm")) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            subject?.let { LetterIcon(it, IconSize.Small) }
            SheetTitle(title)
        }
        SheetText(message)
        SheetActions {
            GlassButton(stringResource(Res.string.action_cancel), ButtonKind.Secondary, onClick = onDismiss)
            GlassButton(confirm, if (destructive) ButtonKind.DangerFill else ButtonKind.Primary, Modifier.testTag("confirm-yes"), onClick = onConfirm)
        }
    }
}

@Composable
fun GlassChooseBinary(
    question: Question.ChooseBinary,
    onPick: (Int) -> Unit,
    onDecline: () -> Unit,
) {
    var picked by remember(question) { mutableIntStateOf(0) }
    val glass = LocalGlass.current
    Sheet(onDecline, Modifier.testTag("question-choose-binary")) {
        SheetTitle(stringResource(Res.string.dialog_choose_binary_title, question.pkg))
        SheetText(stringResource(Res.string.dialog_choose_binary_text, question.pkg))
        Column(Modifier.selectableGroup()) {
            question.candidates.forEachIndexed { index, candidate ->
                Row(
                    Modifier.fillMaxWidth().selectable(index == picked, role = Role.RadioButton, onClick = { picked = index }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = index == picked, onClick = null)
                    Text(candidate, color = glass.ink, style = mono(MONO_SIZE))
                }
            }
        }
        SheetActions {
            GlassButton(stringResource(Res.string.action_cancel), ButtonKind.Secondary, onClick = onDecline)
            GlassButton(stringResource(Res.string.action_link), ButtonKind.Primary) { onPick(picked) }
        }
    }
}

@Composable
fun GlassAbout(
    coreVersion: String,
    onClose: () -> Unit,
) {
    val glass = LocalGlass.current
    val uri = LocalUriHandler.current
    Sheet(onClose, Modifier.testTag("about")) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Mark(MARK_SIZE)
            Column {
                SheetTitle("Ketch")
                Text(stringResource(Res.string.about_version, appVersion, coreVersion), color = glass.ink2, style = mono(MONO_SIZE))
            }
        }
        SheetText(ABOUT_SUMMARY)
        Text(ABOUT_LICENCE_TITLE, color = glass.ink, style = body(TextSize.LABEL, FontWeight.SemiBold))
        SheetText(ABOUT_LICENCES)
        Text(ABOUT_LINK, color = glass.accentInk, style = body(TextSize.LABEL, FontWeight.Medium), modifier = Modifier.clickable { uri.openUri(ABOUT_REPOSITORY) })
        SheetActions { GlassButton(stringResource(Res.string.action_close), ButtonKind.Secondary, onClick = onClose) }
    }
}

private const val MONO_SIZE = 12
private val MARK_SIZE = 48.dp
