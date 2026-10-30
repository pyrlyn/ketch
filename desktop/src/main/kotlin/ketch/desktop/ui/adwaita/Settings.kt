// What the design's toggles for the core's settings would change lives in config.toml, which the app does not write: they
// are shown as values, with the file one press away.
package ketch.desktop.ui.adwaita

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ketch.desktop.settings.AppSettings
import ketch.desktop.store.UpdateChecks
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.Source
import ketch.desktop.ui.screens.LOGIN_NOTE
import ketch.desktop.ui.screens.ROOT_NOTE
import ketch.desktop.ui.screens.TOKEN_NOTE
import ketch.desktop.ui.screens.interval
import ketch.desktop.ui.screens.openConfig
import ketch.desktop.ui.tilde
import ketch.wire.Settings
import kotlinx.coroutines.launch
import pyrlyn.adwaita.ActionRow
import pyrlyn.adwaita.BoxedList
import pyrlyn.adwaita.LocalAdwaita
import pyrlyn.adwaita.LocalAdwaitaType
import pyrlyn.adwaita.PageHead
import pyrlyn.adwaita.PillButton
import pyrlyn.adwaita.PillSize
import pyrlyn.adwaita.PreferencesGroup
import pyrlyn.adwaita.RowLine
import pyrlyn.adwaita.RowMono
import pyrlyn.adwaita.RowText
import pyrlyn.adwaita.RowTitle
import pyrlyn.adwaita.Select
import pyrlyn.adwaita.Status
import pyrlyn.adwaita.StatusPill
import pyrlyn.adwaita.ToggleSwitch
import java.io.File

@Composable
fun AdwaitaSettings(
    parts: AppParts,
    checks: UpdateChecks,
    config: Settings?,
) {
    var message by remember { mutableStateOf<String?>(null) }
    val openFile = { config?.let { message = openConfig(File(it.configFile)) } }
    AdwaitaPage("settings") {
        PageHead("Settings")
        PreferencesGroup("Installation") {
            Rows {
                SettingRow("ketch root", value = config?.let { tilde(it.root) } ?: "—", note = ROOT_NOTE, modifier = Modifier.testTag("settings-root"))
                RowLine()
                SettingRow("Links", value = config?.let { tilde(it.binDir) } ?: "—", note = "Every program ketch installs is linked here.")
                RowLine()
                SettingRow("Create desktop entries", note = "Apps get a .desktop entry in your applications menu.") { config?.let { OnOff(it.linkApps) } }
            }
        }
        PreferencesGroup("Security", suffix = { PillButton("Open config.toml", size = PillSize.Small, enabled = config != null) { openFile() } }) {
            Rows {
                SettingRow("Require checksums", note = "Refuses a release that publishes no checksum.") { config?.let { OnOff(it.requireChecksums) } }
            }
        }
        UpdateSettings(parts, checks) { message = it }
        PreferencesGroup("Sources") {
            Rows {
                SettingRow(Source.Releases.title, value = "github.com/<owner>/<repo>/releases") { StatusPill("Default", Status.Neutral) }
                RowLine()
                SettingRow(Source.Registry.title, value = config?.registry ?: "curated package names → repos")
            }
        }
        PreferencesGroup("GitHub") {
            Rows {
                SettingRow("Access token", note = TOKEN_NOTE) {
                    config?.let { StatusPill(if (it.githubToken) "Set" else "Not set", if (it.githubToken) Status.Ok else Status.Neutral) }
                }
            }
        }
        message?.let { Text(it, color = LocalAdwaita.current.dangerText, style = LocalAdwaitaType.current.caption, modifier = Modifier.testTag("settings-message")) }
    }
}

@Composable
private fun UpdateSettings(
    parts: AppParts,
    checks: UpdateChecks,
    onMessage: (String?) -> Unit,
) {
    val prefs by parts.settings.state.collectAsState()
    // Read from the system each time: the person can change it there too.
    var opensAtLogin by remember { mutableStateOf(parts.login.isEnabled()) }
    val scope = rememberCoroutineScope()
    PreferencesGroup("Updates") {
        Rows {
            SettingRow("Check for updates", note = "How often ketch looks for new releases in the background.") {
                Select(AppSettings.INTERVALS, prefs.intervalMinutes, ::interval, Modifier.testTag("interval"), { Modifier.testTag("interval-$it") }) { minutes ->
                    parts.settings.update { it.copy(intervalMinutes = minutes) }
                }
            }
            RowLine()
            SettingRow("Include prereleases when installing") {
                ToggleSwitch(prefs.prereleases, "Include prereleases when installing", Modifier.testTag("prereleases")) { on -> parts.settings.update { it.copy(prereleases = on) } }
            }
            RowLine()
            SettingRow("Notify when updates are available") {
                ToggleSwitch(prefs.notifies, "Notify when updates are available", Modifier.testTag("notices")) { on -> scope.launch { onMessage(checks.setNotifications(on)) } }
            }
            RowLine()
            SettingRow("Start at login", note = if (parts.login.isAvailable) null else LOGIN_NOTE) {
                ToggleSwitch(opensAtLogin, "Start at login", Modifier.testTag("login"), enabled = parts.login.isAvailable) { on ->
                    onMessage(parts.login.set(on))
                    opensAtLogin = parts.login.isEnabled()
                }
            }
        }
    }
}

@Composable
private fun Rows(content: @Composable ColumnScope.() -> Unit) = BoxedList(content = content)

@Composable
private fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    note: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    ActionRow(modifier, trailing = { trailing() }) {
        RowTitle(title)
        value?.let { RowMono(it) }
        note?.let { RowText(it) }
    }
}

@Composable
private fun OnOff(on: Boolean) = StatusPill(if (on) "On" else "Off", if (on) Status.Ok else Status.Neutral)
