// The bars above every screen: another process holds the lock (with Retry), an operation runs (with its progress and
// Cancel), an error the person has not dismissed.
package ketch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import ketch.desktop.store.StoreState
import ketch.desktop.strings.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun Bars(
    state: StoreState,
    actions: Actions,
) {
    val status = LocalStatusColors.current
    state.busy?.let { busy ->
        Bar(stringResource(Res.string.window_ketch_busy), busy.message, status.busySubtle, status.busy, "busy-bar") {
            TextButton(onClick = { actions.run { retryBusy() } }) { Text(stringResource(Res.string.action_retry)) }
        }
    }
    state.activity?.let { activity ->
        Bar(activity.title, activity.status, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "activity-bar") {
            TextButton(onClick = { actions.store.cancel() }, enabled = !activity.isCancelling) { Text(stringResource(Res.string.action_cancel)) }
        }
        LinearProgressIndicator(progress = { activity.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
    state.error?.let { error ->
        Bar(stringResource(Res.string.window_something_wrong), error, status.errorSubtle, status.error, "error-bar") {
            TextButton(onClick = { actions.store.dismissError() }) { Text(stringResource(Res.string.action_dismiss)) }
        }
    }
}

@Composable
private fun Bar(
    title: String,
    message: String?,
    background: Color,
    ink: Color,
    tag: String,
    action: @Composable () -> Unit,
) {
    val space = LocalSpacing.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = space.lg, vertical = space.sm)
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        }
        action()
    }
}
