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
import ketch.desktop.strings.*
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
import org.jetbrains.compose.resources.stringResource
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
        PageHead(stringResource(Res.string.section_settings))
        PreferencesGroup(stringResource(Res.string.settings_installation)) {
            Rows {
                SettingRow(stringResource(Res.string.settings_root), value = config?.let { tilde(it.root) } ?: "—", note = ROOT_NOTE, modifier = Modifier.testTag("settings-root"))
                RowLine()
                SettingRow(stringResource(Res.string.settings_links), value = config?.let { tilde(it.binDir) } ?: "—", note = stringResource(Res.string.settings_links_note))
                RowLine()
                SettingRow(stringResource(Res.string.settings_desktop_entries), note = stringResource(Res.string.settings_desktop_entries_note)) { config?.let { OnOff(it.linkApps) } }
            }
        }
        PreferencesGroup(stringResource(Res.string.settings_security), suffix = { PillButton(stringResource(Res.string.action_open_config), size = PillSize.Small, enabled = config != null) { openFile() } }) {
            Rows {
                SettingRow(stringResource(Res.string.settings_checksums), note = stringResource(Res.string.settings_checksums_note)) { config?.let { OnOff(it.requireChecksums) } }
            }
        }
        UpdateSettings(parts, checks) { message = it }
        PreferencesGroup(stringResource(Res.string.section_sources)) {
            Rows {
                SettingRow(Source.Releases.title, value = "github.com/<owner>/<repo>/releases") { StatusPill(stringResource(Res.string.status_default), Status.Neutral) }
                RowLine()
                SettingRow(Source.Registry.title, value = config?.registry ?: stringResource(Res.string.settings_registry_note))
            }
        }
        PreferencesGroup("GitHub") {
            Rows {
                SettingRow(stringResource(Res.string.settings_access_token), note = TOKEN_NOTE) {
                    config?.let { StatusPill(if (it.githubToken) stringResource(Res.string.status_set) else stringResource(Res.string.status_not_set), if (it.githubToken) Status.Ok else Status.Neutral) }
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
    PreferencesGroup(stringResource(Res.string.section_updates)) {
        Rows {
            SettingRow(stringResource(Res.string.settings_check_updates), note = stringResource(Res.string.settings_check_updates_note)) {
                Select(AppSettings.INTERVALS, prefs.intervalMinutes, ::interval, Modifier.testTag("interval"), { Modifier.testTag("interval-$it") }) { minutes ->
                    parts.settings.update { it.copy(intervalMinutes = minutes) }
                }
            }
            RowLine()
            SettingRow(stringResource(Res.string.settings_prereleases)) {
                ToggleSwitch(prefs.prereleases, stringResource(Res.string.settings_prereleases), Modifier.testTag("prereleases")) { on -> parts.settings.update { it.copy(prereleases = on) } }
            }
            RowLine()
            SettingRow(stringResource(Res.string.settings_notify)) {
                ToggleSwitch(prefs.notifies, stringResource(Res.string.settings_notify), Modifier.testTag("notices")) { on -> scope.launch { onMessage(checks.setNotifications(on)) } }
            }
            RowLine()
            SettingRow(stringResource(Res.string.settings_start_at_login), note = if (parts.login.isAvailable) null else LOGIN_NOTE) {
                ToggleSwitch(opensAtLogin, stringResource(Res.string.settings_start_at_login), Modifier.testTag("login"), enabled = parts.login.isAvailable) { on ->
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
private fun OnOff(on: Boolean) = StatusPill(if (on) stringResource(Res.string.status_on) else stringResource(Res.string.status_off), if (on) Status.Ok else Status.Neutral)
