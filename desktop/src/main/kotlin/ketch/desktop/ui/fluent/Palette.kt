// The Windows look's colours and fonts. The person's Appearance choices are resolved here, so no view reads the stored
// settings.
package ketch.desktop.ui.fluent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.Prefs
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.LocalAppearance
import ketch.desktop.ui.glass.rgb
import ketch.desktop.ui.installedFamilies
import ketch.desktop.ui.systemFamily
import pyrlyn.fluent.FluentColors
import pyrlyn.fluent.FluentFonts
import pyrlyn.fluent.FluentTheme
import pyrlyn.fluent.LocalWindowBackdrop
import pyrlyn.fluent.Preset
import pyrlyn.fluent.Presets
import pyrlyn.fluent.WindowMaterial

/** The name a preset is stored under. */
val Preset.stored get() = name.lowercase()

fun fluentColors(
    appearance: Appearance,
    prefs: Prefs,
): FluentColors {
    val material =
        when (prefs.material) {
            Prefs.MICA -> WindowMaterial.Mica
            Prefs.TRANSPARENT -> WindowMaterial.Transparent
            else -> WindowMaterial.Acrylic
        }
    val colors =
        (if (appearance.isDark) FluentColors.Dark else FluentColors.Light).copy(
            material = material,
            tint = chosen(Presets.tints, prefs.tint),
            wash = prefs.wash.toFloat(),
        )
    return chosen(Presets.accents, prefs.accent)?.let(colors::withAccent) ?: colors
}

/** A preset's name or a stored `#rrggbb`; anything else is the standard choice. */
private fun chosen(
    presets: List<Preset>,
    stored: String,
): Color? = presets.firstOrNull { it.stored == stored }?.color ?: rgb(stored)

/** The first family of each list that this machine has; a machine with none of them keeps Compose's defaults. */
fun fluentFonts(families: Set<String> = installedFamilies()): FluentFonts {
    val standard = FluentFonts()
    return FluentFonts(
        text = systemFamily(TEXT, families) ?: standard.text,
        display = systemFamily(DISPLAY, families) ?: standard.display,
        mono = systemFamily(MONO, families) ?: standard.mono,
    )
}

@Composable
fun KetchFluent(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val prefs by settings.state.collectAsState()
    val appearance = LocalAppearance.current
    val fonts = remember { fluentFonts() }
    val colors = remember(prefs, appearance) { fluentColors(appearance, prefs) }
    val chrome = LocalWindowChrome.current
    var backdrop by remember { mutableStateOf(false) }
    LaunchedEffect(chrome, colors.material, appearance) { backdrop = chrome?.wear(colors.material, appearance == Appearance.Dark) == true }
    CompositionLocalProvider(LocalWindowBackdrop provides backdrop) { FluentTheme(colors, fonts, content) }
}

private val TEXT = listOf("Segoe UI Variable Text", "Segoe UI", "Inter")
private val DISPLAY = listOf("Segoe UI Variable Display", "Segoe UI", "Inter")
private val MONO = listOf("JetBrains Mono", "Cascadia Mono", "Consolas")
