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
import ketch.desktop.ui.Actions
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.LocalMonoStyle
import ketch.desktop.ui.LocalSpacing
import ketch.desktop.ui.LocalStatusColors
import ketch.wire.Settings
import kotlinx.coroutines.launch
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
    Page("Settings", "settings", buttons = { TextButton(onClick = onAbout) { Text("About Ketch") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(space.lg)) {
            Column(Modifier.selectableGroup()) {
                Text("Check for updates", style = MaterialTheme.typography.titleSmall)
                for (minutes in AppSettings.INTERVALS) {
                    Choice(interval(minutes), prefs.intervalMinutes == minutes, "interval-$minutes") {
                        parts.settings.update { it.copy(intervalMinutes = minutes) }
                    }
                }
            }
            Toggle("Include prereleases when installing", prefs.prereleases, "prereleases") { on ->
                parts.settings.update { it.copy(prereleases = on) }
            }
            Toggle("Notify when updates are available", prefs.notifies, "notices") { on ->
                scope.launch { message = checks.setNotifications(on) }
            }
            Column {
                Toggle("Open at login", opensAtLogin, "login", enabled = parts.login.isAvailable) { on ->
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
        Text("ketch root", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(config?.root ?: "—", style = LocalMonoStyle.current, modifier = Modifier.testTag("settings-root")) }
        Caption(ROOT_NOTE)
        OutlinedButton(onClick = { config?.let { onMessage(openConfig(File(it.configFile)) ?: return@let) } }, enabled = config != null) {
            Text("Open config.toml")
        }
        Caption(TOKEN_NOTE)
    }
}

const val ROOT_NOTE = "Shared with the ketch CLI. Set KETCH_ROOT in the app's environment to use another root."
const val TOKEN_NOTE =
    "The app stores no tokens. ketch reads a GitHub token from its config.toml or KETCH_GITHUB_TOKEN, " +
        "GITHUB_TOKEN or GH_TOKEN, as the CLI does."
const val LOGIN_NOTE = "Available in the installed app."

/** Opens [file] in the system's editor; what to tell the person when it cannot. */
fun openConfig(file: File): String? =
    when {
        !file.isFile -> {
            "${file.path} does not exist yet; ketch runs on its defaults until it does."
        }

        !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN) -> {
            "This system cannot open ${file.path}."
        }

        else -> {
            try {
                Desktop.getDesktop().open(file)
                null
            } catch (e: IOException) {
                "Could not open ${file.path}: ${e.message}"
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
        minutes < MINUTES_PER_HOUR -> "Every $minutes minutes"
        minutes == MINUTES_PER_HOUR -> "Every hour"
        minutes == MINUTES_PER_DAY -> "Every day"
        else -> "Every ${minutes / MINUTES_PER_HOUR} hours"
    }

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1440
