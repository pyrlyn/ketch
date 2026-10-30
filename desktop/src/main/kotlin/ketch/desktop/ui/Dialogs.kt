// The dialogs: the questions the core waits on, and the confirmations before something is removed or changed. A
// dismissed question is declined, never left open, so the core is not kept waiting.
package ketch.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import ketch.desktop.store.StoreState
import ketch.desktop.ui.fluent.FluentChooseBinary
import ketch.desktop.ui.fluent.FluentConfirm
import ketch.desktop.ui.glass.GlassChooseBinary
import ketch.desktop.ui.glass.GlassConfirm
import ketch.wire.Answer
import ketch.wire.Question

@Composable
fun Questions(
    state: StoreState,
    actions: Actions,
) {
    val pending = state.pending ?: return
    val store = actions.store
    when (val question = pending.question) {
        is Question.ChooseBinary -> {
            ChooseBinary(question, onPick = { store.answer(Answer.Index(it)) }, onDecline = store::decline)
        }

        is Question.StopProcesses -> {
            val list = question.holders.joinToString("\n") { "${it.path} (pid ${it.pid})" }
            Confirm(
                title = "Stop running programs?",
                message = "These programs use files the upgrade replaces:\n\n$list",
                confirm = "Stop them",
                destructive = true,
                onConfirm = { store.answer(Answer.Confirm(true)) },
                onDismiss = store::decline,
            )
        }

        is Question.ConfirmRemoveLeftover -> {
            Confirm(
                title = "Remove a leftover folder?",
                message = "${question.path} was left behind by an earlier uninstall, and ketch recorded nothing in it.",
                confirm = "Remove",
                destructive = true,
                onConfirm = { store.answer(Answer.Confirm(true)) },
                onDismiss = store::decline,
            )
        }
    }
}

@Composable
private fun ChooseBinary(
    question: Question.ChooseBinary,
    onPick: (Int) -> Unit,
    onDecline: () -> Unit,
) {
    when (LocalLook.current) {
        Look.Glass -> {
            GlassChooseBinary(question, onPick, onDecline)
            return
        }

        Look.Fluent -> {
            FluentChooseBinary(question, onPick, onDecline)
            return
        }

        Look.Material -> {}
    }
    var picked by remember(question) { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDecline,
        modifier = Modifier.testTag("question-choose-binary"),
        title = { Text("Which binary should ${question.pkg} link?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text("${question.pkg} ships several programs. ketch links one onto your PATH.")
                Column(Modifier.selectableGroup()) {
                    question.candidates.forEachIndexed { index, candidate ->
                        Row(
                            Modifier.selectable(index == picked, role = Role.RadioButton, onClick = { picked = index }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == picked, onClick = null)
                            Text(candidate, style = LocalMonoStyle.current)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(picked) }) { Text("Link") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Cancel") } },
    )
}

@Composable
fun Confirm(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean = false,
    /** The package this is about, for the look that shows its icon. */
    subject: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (LocalLook.current) {
        Look.Glass -> {
            GlassConfirm(title, message, confirm, destructive, subject, onConfirm, onDismiss)
            return
        }

        Look.Fluent -> {
            FluentConfirm(title, message, confirm, destructive, subject, onConfirm, onDismiss)
            return
        }

        Look.Material -> {}
    }
    val colors =
        if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("confirm"),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm, colors = colors, modifier = Modifier.testTag("confirm-yes")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
