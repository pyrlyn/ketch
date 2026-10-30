// Settings: what the other platforms show, plus the look itself. The preferences are the app's own; ketch's config stays
// in its config.toml.
package ketch.desktop.ui.fluent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.Prefs
import ketch.desktop.store.UpdateChecks
import ketch.desktop.strings.*
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.colorName
import ketch.desktop.ui.screens.LOGIN_NOTE
import ketch.desktop.ui.screens.ROOT_NOTE
import ketch.desktop.ui.screens.TOKEN_NOTE
import ketch.desktop.ui.screens.interval
import ketch.desktop.ui.screens.openConfig
import ketch.desktop.ui.tilde
import ketch.wire.Settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pyrlyn.fluent.ButtonStyle
import pyrlyn.fluent.ComboBox
import pyrlyn.fluent.FluentButton
import pyrlyn.fluent.FluentSlider
import pyrlyn.fluent.LocalFluent
import pyrlyn.fluent.LocalFluentType
import pyrlyn.fluent.PageHead
import pyrlyn.fluent.PageTitle
import pyrlyn.fluent.Pill
import pyrlyn.fluent.Preset
import pyrlyn.fluent.Presets
import pyrlyn.fluent.RadioButtons
import pyrlyn.fluent.RowGroup
import pyrlyn.fluent.SelectorBar
import pyrlyn.fluent.SettingsCard
import pyrlyn.fluent.Swatch
import pyrlyn.fluent.ToggleSwitch
import pyrlyn.glass.Glyph
import java.io.File
import kotlin.math.roundToInt

private enum class SettingsPane(
    val title: String,
) {
    General(tr(Res.string.settings_general)),
    Appearance(tr(Res.string.settings_appearance)),
}

@Composable
fun FluentSettings(
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
    config: Settings?,
) {
    var pane by remember { mutableStateOf(SettingsPane.General) }
    FluentPage("settings") {
        PageHead(
            title = { PageTitle(stringResource(Res.string.section_settings)) },
            actions = { SelectorBar(SettingsPane.entries, pane, { it.title }, itemModifier = { Modifier.testTag("settings-${it.name.lowercase()}") }) { pane = it } },
        )
        when (pane) {
            SettingsPane.General -> General(parts, checks, config, navigation)
            SettingsPane.Appearance -> Appearance(parts.settings)
        }
    }
}

@Composable
private fun General(
    parts: AppParts,
    checks: UpdateChecks,
    config: Settings?,
    navigation: Navigation,
) {
    val prefs by parts.settings.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    // Read from the system each time: the person can change it there too.
    var opensAtLogin by remember { mutableStateOf(parts.login.isEnabled()) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        RowGroup {
            SettingsCard(stringResource(Res.string.settings_check_updates), glyph = Glyph.Refresh, note = stringResource(Res.string.settings_check_updates_note)) {
                ComboBox(AppSettings.INTERVALS, prefs.intervalMinutes, ::interval, Modifier.testTag("interval"), { Modifier.testTag("interval-$it") }) { minutes ->
                    parts.settings.update { it.copy(intervalMinutes = minutes) }
                }
            }
            SettingsCard(stringResource(Res.string.settings_prereleases), glyph = Glyph.Download) {
                ToggleSwitch(prefs.prereleases, stringResource(Res.string.settings_prereleases), Modifier.testTag("prereleases")) { on -> parts.settings.update { it.copy(prereleases = on) } }
            }
            SettingsCard(stringResource(Res.string.settings_notify), glyph = Glyph.Bell, note = stringResource(Res.string.settings_notify_windows)) {
                ToggleSwitch(prefs.notifies, stringResource(Res.string.settings_notify), Modifier.testTag("notices")) { on -> scope.launch { message = checks.setNotifications(on) } }
            }
            SettingsCard(stringResource(Res.string.settings_start_with_windows), glyph = Glyph.Tiles, note = if (parts.login.isAvailable) null else LOGIN_NOTE) {
                ToggleSwitch(opensAtLogin, stringResource(Res.string.settings_start_with_windows), Modifier.testTag("login"), enabled = parts.login.isAvailable) { on ->
                    message = parts.login.set(on)
                    opensAtLogin = parts.login.isEnabled()
                }
            }
        }
        RowGroup {
            SettingsCard(stringResource(Res.string.settings_root), glyph = Glyph.Folder, note = ROOT_NOTE) { Pill(config?.let { tilde(it.root) } ?: "—", Modifier.testTag("settings-root"), code = true) }
            SettingsCard(stringResource(Res.string.settings_configuration), glyph = Glyph.Document, note = TOKEN_NOTE) {
                FluentButton(stringResource(Res.string.action_open_config), enabled = config != null) { config?.let { message = openConfig(File(it.configFile)) } }
            }
        }
        message?.let { Text(it, color = LocalFluent.current.danger, style = LocalFluentType.current.small, modifier = Modifier.testTag("settings-message")) }
        FluentButton(stringResource(Res.string.settings_about_ketch), ButtonStyle.Subtle) { navigation.showingAbout = true }
    }
}

@Composable
private fun Appearance(settings: AppSettings) {
    val c = LocalFluent.current
    val type = LocalFluentType.current
    val prefs by settings.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        RowGroup {
            SettingsCard(stringResource(Res.string.settings_window_material), glyph = Glyph.Window, note = stringResource(Res.string.settings_window_material_note)) {
                RadioButtons(MATERIALS, prefs.material, { materialTitle(it) }, optionModifier = { Modifier.testTag("material-$it") }) { material ->
                    settings.update { it.copy(material = material) }
                }
            }
            SettingsCard(stringResource(Res.string.settings_tint), glyph = Glyph.Contrast) {
                Swatches(null, Prefs.STANDARD_TINT, stringResource(Res.string.settings_no_tint), Presets.tints, prefs.tint) { name -> settings.update { it.copy(tint = name) } }
            }
            SettingsCard(stringResource(Res.string.settings_accent_colour), glyph = Glyph.Palette, note = stringResource(Res.string.settings_automatic_note)) {
                Swatches(Presets.standardAccent, Prefs.STANDARD_ACCENT, stringResource(Res.string.settings_automatic), Presets.accents, prefs.accent) { name -> settings.update { it.copy(accent = name) } }
            }
            SettingsCard(stringResource(Res.string.settings_backdrop_wash), glyph = Glyph.Waves) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FluentSlider((prefs.wash * PERCENT).toFloat(), 0f..(Prefs.MAX_WASH * PERCENT).toFloat(), stringResource(Res.string.settings_backdrop_wash), Modifier.width(SLIDER_WIDTH).testTag("appearance-wash")) { percent ->
                        settings.update { it.copy(wash = percent.roundToInt() / PERCENT) }
                    }
                    Text("${(prefs.wash * PERCENT).roundToInt()}%", color = c.ink2, style = type.mono, textAlign = TextAlign.End, modifier = Modifier.width(VALUE_WIDTH))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.settings_changes_pc), color = c.ink2, style = type.small)
            Spacer(Modifier.weight(1f))
            FluentButton(stringResource(Res.string.action_reset), modifier = Modifier.testTag("appearance-reset")) { settings.update(Prefs::standardLook) }
        }
    }
}

@Composable
private fun Swatches(
    standardColor: Color?,
    standard: String,
    standardLabel: String,
    presets: List<Preset>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Swatch(standardColor, standardLabel, selected == standard, Modifier.testTag("swatch-$standard")) { onPick(standard) }
        for (preset in presets) Swatch(preset.color, colorName(preset.stored), selected == preset.stored, Modifier.testTag("swatch-${preset.stored}")) { onPick(preset.stored) }
    }
}

private const val PERCENT = 100.0
private val MATERIALS = listOf(Prefs.MICA, Prefs.ACRYLIC, Prefs.TRANSPARENT)

private fun materialTitle(material: String) =
    when (material) {
        Prefs.MICA -> tr(Res.string.material_mica)
        Prefs.ACRYLIC -> tr(Res.string.material_acrylic)
        else -> tr(Res.string.material_transparent)
    }

private val GROUP_GAP = 24.dp
private val SLIDER_WIDTH = 200.dp
private val VALUE_WIDTH = 36.dp
