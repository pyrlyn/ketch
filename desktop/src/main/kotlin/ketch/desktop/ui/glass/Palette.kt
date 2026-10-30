// The macOS look's colours: tokens.json mapped onto the components' slots. The person's Appearance choices are resolved
// here too, so no view reads the stored settings.
package ketch.desktop.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.Prefs
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.LocalAppearance
import ketch.desktop.ui.Tokens
import pyrlyn.glass.GlassColors
import pyrlyn.glass.GlassTheme

val TINTS = listOf("sky", "mint", "sand", "rose", "lilac", "smoke")
val ACCENTS = listOf("blue", "purple", "pink", "red", "orange", "yellow", "green", "graphite")

/** What Settings -> Appearance chose, after the rules below. */
@Immutable
data class GlassChoices(
    val tint: Color? = null,
    val clear: Boolean = false,
    val accent: Color? = null,
    /** How strongly the wallpaper shows, 1 at the standard wash. */
    val wash: Float = 1f,
)

fun glassColors(
    tokens: Tokens,
    appearance: Appearance,
    choices: GlassChoices,
): GlassColors {
    fun mac(path: String) = tokens.macColor(path, appearance)

    fun brand(path: String) = tokens.color(path, appearance)

    val dark = appearance.isDark
    val accent = choices.accent ?: brand("accent.default")
    return GlassColors(
        dark = dark,
        ink = mac("text.primary"),
        ink2 = mac("text.secondary"),
        accent = accent,
        accentPressed = choices.accent?.let { Color.Black.copy(alpha = ACCENT_SHADE).compositeOver(it) } ?: brand("accent.pressed"),
        accentSubtle = accent.copy(alpha = brand("accent.subtle").alpha),
        accentOn = if (accent.luminance() > LIGHT_ACCENT) TEXT_ON_LIGHT_ACCENT else brand("accent.on"),
        accentInk = choices.accent ?: brand("accent.ink"),
        success = brand("status.installed"),
        successSubtle = brand("status.installedSubtle"),
        attention = brand("status.update"),
        attentionSubtle = brand("status.updateSubtle"),
        attentionFill = if (dark) UPDATE_FILL_DARK else UPDATE_FILL,
        attentionOn = if (dark) UPDATE_ON_DARK else Color.White,
        progress = brand("status.busy"),
        progressSubtle = brand("status.busySubtle"),
        warning = brand("status.warning"),
        warningSubtle = brand("status.warningSubtle"),
        danger = brand("status.error"),
        dangerSubtle = brand("status.errorSubtle"),
        base = mac("background.base"),
        control = mac("glass.control"),
        highlight = mac("glass.highlight"),
        shade = mac("glass.shade"),
        solid = mac("glass.solidElevated"),
        separator = mac("separator"),
        fill = mac("fill.control"),
        hover = mac("fill.hover"),
        track = mac("fill.track"),
        focus = mac("focusRing"),
        scrim = mac("scrim"),
        tint = choices.tint,
        tintOpacity = tokens.opacity.getValue(if (dark) "tintDark" else "tint"),
        clear = choices.clear,
        wash = choices.wash,
    )
}

/** A stored `#rrggbb`, which the macOS app's custom colours are; anything else is no colour. */
fun rgb(stored: String): Color? {
    val digits = stored.removePrefix("#")
    if (!stored.startsWith("#") || digits.length != HEX_DIGITS) return null
    return digits.toLongOrNull(radix = 16)?.let { Color(OPAQUE or it) }
}

/** The stored choices as colours: a name that is no preset and no `#rrggbb` is the standard choice. */
fun resolve(
    prefs: Prefs,
    tokens: Tokens,
    appearance: Appearance,
): GlassChoices {
    fun preset(
        kind: String,
        names: List<String>,
        stored: String,
    ) = if (stored in names) tokens.macColor("preset.$kind.$stored", appearance) else rgb(stored)
    return GlassChoices(
        tint = preset("tint", TINTS, prefs.tint),
        clear = prefs.glass == Prefs.CLEAR_GLASS,
        accent = preset("accent", ACCENTS, prefs.accent),
        wash = prefs.wash.toFloat() / tokens.opacity.getValue("wash"),
    )
}

/** The look in this appearance with the person's choices, over the Material theme the Markdown and the slider use. */
@Composable
fun KetchGlass(
    settings: AppSettings,
    tokens: Tokens = Tokens.brand,
    content: @Composable () -> Unit,
) {
    val prefs by settings.state.collectAsState()
    val appearance = LocalAppearance.current
    GlassTheme(remember(prefs, appearance, tokens) { glassColors(tokens, appearance, resolve(prefs, tokens, appearance)) }, content)
}

private const val ACCENT_SHADE = 0.14f
private const val LIGHT_ACCENT = 0.5f
private const val HEX_DIGITS = 6
private const val OPAQUE = 0xFF000000
private val TEXT_ON_LIGHT_ACCENT = Color(0xFF0D1B2A)
private val UPDATE_FILL = Color(0xFFC25A06)
private val UPDATE_FILL_DARK = Color(0xFFFFA860)
private val UPDATE_ON_DARK = Color(0xFF2A1400)
