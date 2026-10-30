// Settings: how often to check for updates, prereleases, notices, open at login, and where ketch's own configuration
// lives. The app keeps no copy of ketch's settings and no tokens.
package ketch.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import ketch.desktop.settings.AppSettings
import ketch.desktop.store.UpdateChecks
import ketch.desktop.strings.*
import ketch.desktop.ui.Actions
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.LocalMonoStyle
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import ketch.wire.Settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.io.File
import java.io.IOException

@Composable
fun SettingsScreen(
    actions: Actions,
    parts: AppParts,
    checks: UpdateChecks,
    onAbout: () -> Unit,
) {
    val prefs by parts.settings.state.collectAsState()
    var config by remember { mutableStateOf<Settings?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Read from the system each time: the person can change it there too.
    var opensAtLogin by remember { mutableStateOf(parts.login.isEnabled()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { config = actions.store.config() }
    val space = LocalSpacing.current
    Page(stringResource(Res.string.section_settings), "settings", buttons = { TextButton(onClick = onAbout) { Text(stringResource(Res.string.settings_about_ketch)) } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(space.lg)) {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(Res.string.settings_check_updates), style = MaterialTheme.typography.titleSmall)
                for (minutes in AppSettings.INTERVALS) {
                    Choice(interval(minutes), prefs.intervalMinutes == minutes, "interval-$minutes") {
                        parts.settings.update { it.copy(intervalMinutes = minutes) }
                    }
                }
            }
            Toggle(stringResource(Res.string.settings_prereleases), prefs.prereleases, "prereleases") { on ->
                parts.settings.update { it.copy(prereleases = on) }
            }
            Toggle(stringResource(Res.string.settings_notify), prefs.notifies, "notices") { on ->
                scope.launch { message = checks.setNotifications(on) }
            }
            Column {
                Toggle(stringResource(Res.string.settings_open_at_login), opensAtLogin, "login", enabled = parts.login.isAvailable) { on ->
                    message = parts.login.set(on)
                    opensAtLogin = parts.login.isEnabled()
                }
                if (!parts.login.isAvailable) Caption(LOGIN_NOTE)
            }
            Root(config) { message = it }
            message?.let { Text(it, color = LocalStatusColors.current.error, modifier = Modifier.testTag("settings-message")) }
        }
    }
}

@Composable
private fun Root(
    config: Settings?,
    onMessage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        Text(stringResource(Res.string.settings_root), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(config?.root ?: "—", style = LocalMonoStyle.current, modifier = Modifier.testTag("settings-root")) }
        Caption(ROOT_NOTE)
        OutlinedButton(onClick = { config?.let { onMessage(openConfig(File(it.configFile)) ?: return@let) } }, enabled = config != null) {
            Text(stringResource(Res.string.action_open_config))
        }
        Caption(TOKEN_NOTE)
    }
}

val ROOT_NOTE get() = tr(Res.string.settings_root_note)
val TOKEN_NOTE get() = tr(Res.string.settings_token_note)
val LOGIN_NOTE get() = tr(Res.string.settings_login_note)

/** Opens [file] in the system's editor; what to tell the person when it cannot. */
fun openConfig(file: File): String? =
    when {
        !file.isFile -> {
            tr(Res.string.settings_config_missing, file.path)
        }

        !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN) -> {
            tr(Res.string.settings_cannot_open, file.path)
        }

        else -> {
            try {
                Desktop.getDesktop().open(file)
                null
            } catch (e: IOException) {
                tr(Res.string.settings_open_failed, file.path, e.message.orEmpty())
            }
        }
    }

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = LocalSpacing.current.xxs)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = LocalSpacing.current.sm))
    }
}

@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    tag: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

fun interval(minutes: Int) =
    when {
        minutes < MINUTES_PER_HOUR -> plural(minutes, Res.plurals.settings_every_minutes)
        minutes == MINUTES_PER_DAY -> tr(Res.string.settings_every_day)
        else -> plural(minutes / MINUTES_PER_HOUR, Res.plurals.settings_every_hours)
    }

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1440
