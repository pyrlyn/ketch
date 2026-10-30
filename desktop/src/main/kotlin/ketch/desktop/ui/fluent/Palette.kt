// The Windows look's colours and fonts. The person's Appearance choices are resolved here, so no view reads the stored
// settings.
package ketch.desktop.ui.fluent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.Prefs
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.LocalAppearance
import ketch.desktop.ui.glass.rgb
import pyrlyn.fluent.FluentColors
import pyrlyn.fluent.FluentFonts
import pyrlyn.fluent.FluentTheme
import pyrlyn.fluent.Preset
import pyrlyn.fluent.Presets
import pyrlyn.fluent.WindowMaterial
import java.awt.GraphicsEnvironment

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
@OptIn(ExperimentalTextApi::class)
fun fluentFonts(families: Set<String> = installedFamilies()): FluentFonts {
    fun first(names: List<String>) =
        names.firstOrNull { it in families }?.let { name ->
            FontFamily(WEIGHTS.map { SystemFont(name, it) })
        }
    val standard = FluentFonts()
    return FluentFonts(
        text = first(TEXT) ?: standard.text,
        display = first(DISPLAY) ?: standard.display,
        mono = first(MONO) ?: standard.mono,
    )
}

private fun installedFamilies() = if (GraphicsEnvironment.isHeadless()) emptySet() else GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()

@Composable
fun KetchFluent(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val prefs by settings.state.collectAsState()
    val appearance = LocalAppearance.current
    val fonts = remember { fluentFonts() }
    FluentTheme(remember(prefs, appearance) { fluentColors(appearance, prefs) }, fonts, content)
}

private val TEXT = listOf("Segoe UI Variable Text", "Segoe UI", "Inter")
private val DISPLAY = listOf("Segoe UI Variable Display", "Segoe UI", "Inter")
private val MONO = listOf("JetBrains Mono", "Cascadia Mono", "Consolas")
private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
