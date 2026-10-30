// Settings: what the other platforms show, plus the look itself. The preferences are the app's own; ketch's config stays
// in its config.toml.
package ketch.desktop.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.Prefs
import ketch.desktop.store.UpdateChecks
import ketch.desktop.strings.*
import ketch.desktop.ui.AppParts
import ketch.desktop.ui.LocalAppearance
import ketch.desktop.ui.Navigation
import ketch.desktop.ui.Tokens
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
import pyrlyn.glass.ButtonKind
import pyrlyn.glass.FactChip
import pyrlyn.glass.FieldRow
import pyrlyn.glass.GlassButton
import pyrlyn.glass.GlassSlider
import pyrlyn.glass.GlassSwitch
import pyrlyn.glass.LocalGlass
import pyrlyn.glass.Pane
import pyrlyn.glass.Segmented
import pyrlyn.glass.Select
import pyrlyn.glass.Swatch
import pyrlyn.glass.TextSize
import pyrlyn.glass.body
import pyrlyn.glass.glass
import pyrlyn.glass.mono
import java.io.File
import kotlin.math.roundToInt

private fun glassTitle(style: String) = tr(if (style == Prefs.CLEAR_GLASS) Res.string.glass_clear else Res.string.glass_regular)

private enum class SettingsPane(
    val title: String,
) {
    General(tr(Res.string.settings_general)),
    Appearance(tr(Res.string.settings_appearance)),
}

@Composable
fun GlassSettings(
    navigation: Navigation,
    parts: AppParts,
    checks: UpdateChecks,
    config: Settings?,
) {
    var pane by remember { mutableStateOf(SettingsPane.General) }
    GlassPage(
        stringResource(Res.string.section_settings),
        "settings",
        navigation,
        toolbar = { Segmented(SettingsPane.entries, pane, { it.title }, { Modifier.testTag("settings-${it.name.lowercase()}") }) { pane = it } },
    ) {
        PageColumn(Modifier.widthIn(max = PAGE_WIDTH)) {
            when (pane) {
                SettingsPane.General -> General(parts, checks, config, navigation)
                SettingsPane.Appearance -> Appearance(parts.settings)
            }
        }
    }
}

@Composable
private fun Form(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(Pane.Card).padding(horizontal = 22.dp, vertical = 6.dp)) { content() }
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
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Form {
            FieldRow(stringResource(Res.string.settings_check_updates)) {
                Select(AppSettings.INTERVALS, prefs.intervalMinutes, ::interval, Modifier.testTag("interval"), { Modifier.testTag("interval-$it") }) { minutes ->
                    parts.settings.update { it.copy(intervalMinutes = minutes) }
                }
            }
            FieldRow(stringResource(Res.string.settings_prereleases)) {
                GlassSwitch(prefs.prereleases, stringResource(Res.string.settings_prereleases), Modifier.testTag("prereleases")) { on -> parts.settings.update { it.copy(prereleases = on) } }
            }
            FieldRow(stringResource(Res.string.settings_notify)) {
                GlassSwitch(prefs.notifies, stringResource(Res.string.settings_notify), Modifier.testTag("notices")) { on -> scope.launch { message = checks.setNotifications(on) } }
            }
            FieldRow(stringResource(Res.string.settings_open_at_login), if (parts.login.isAvailable) null else LOGIN_NOTE, last = true) {
                GlassSwitch(opensAtLogin, stringResource(Res.string.settings_open_at_login), Modifier.testTag("login"), enabled = parts.login.isAvailable) { on ->
                    message = parts.login.set(on)
                    opensAtLogin = parts.login.isEnabled()
                }
            }
        }
        Form {
            FieldRow(stringResource(Res.string.settings_root), ROOT_NOTE) { FactChip(config?.let { tilde(it.root) } ?: "—", Modifier.testTag("settings-root")) }
            FieldRow(stringResource(Res.string.settings_configuration), TOKEN_NOTE, last = true) {
                GlassButton(stringResource(Res.string.action_open_config), ButtonKind.Secondary, enabled = config != null) {
                    config?.let { message = openConfig(File(it.configFile)) }
                }
            }
        }
        message?.let { Text(it, color = LocalGlass.current.danger, style = body(TextSize.LABEL), modifier = Modifier.testTag("settings-message")) }
        GlassButton(stringResource(Res.string.settings_about_ketch), ButtonKind.Ghost) { navigation.showingAbout = true }
    }
}

@Composable
private fun Appearance(settings: AppSettings) {
    val glass = LocalGlass.current
    val prefs by settings.state.collectAsState()
    val appearance = LocalAppearance.current

    fun preset(path: String) = Tokens.brand.macColor(path, appearance)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Form {
            FieldRow(stringResource(Res.string.settings_tint)) {
                Swatches(Prefs.STANDARD_TINT, stringResource(Res.string.settings_no_tint), TINTS, prefs.tint, { preset("preset.tint.$it") }) { name -> settings.update { it.copy(tint = name) } }
            }
            FieldRow(stringResource(Res.string.settings_glass)) {
                Segmented(listOf(Prefs.REGULAR_GLASS, Prefs.CLEAR_GLASS), prefs.glass, { glassTitle(it) }) { style ->
                    settings.update { it.copy(glass = style) }
                }
            }
            FieldRow(stringResource(Res.string.settings_accent)) {
                Swatches(Prefs.STANDARD_ACCENT, stringResource(Res.string.settings_system_accent), ACCENTS, prefs.accent, { preset("preset.accent.$it") }) { name -> settings.update { it.copy(accent = name) } }
            }
            FieldRow(stringResource(Res.string.settings_backdrop_wash), last = true) {
                GlassSlider((prefs.wash * PERCENT).toFloat(), 0f..(Prefs.MAX_WASH * PERCENT).toFloat(), stringResource(Res.string.settings_backdrop_wash), Modifier.width(SLIDER_WIDTH).testTag("appearance-wash")) { percent ->
                    settings.update { it.copy(wash = percent.roundToInt() / PERCENT) }
                }
                Text("${(prefs.wash * PERCENT).roundToInt()}%", color = glass.ink2, style = mono(VALUE), modifier = Modifier.width(VALUE_WIDTH))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.settings_changes_mac), color = glass.ink2, style = body(TextSize.LABEL), modifier = Modifier.weight(1f))
            GlassButton(stringResource(Res.string.action_reset), ButtonKind.Ghost, Modifier.testTag("appearance-reset")) { settings.update(Prefs::standardLook) }
        }
    }
}

@Composable
private fun Swatches(
    standard: String,
    standardLabel: String,
    names: List<String>,
    selected: String,
    color: (String) -> Color,
    onPick: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Swatch(null, standardLabel, selected == standard, Modifier.testTag("swatch-$standard")) { onPick(standard) }
        for (name in names) Swatch(color(name), colorName(name), selected == name, Modifier.testTag("swatch-$name")) { onPick(name) }
    }
}

private const val PERCENT = 100.0
private const val VALUE = 12
private val PAGE_WIDTH = 860.dp
private val SLIDER_WIDTH = 200.dp
private val VALUE_WIDTH = 34.dp
