// The Linux look's fonts. The look is dark whatever the desktop is, as its design is: everything else comes from the package.
package ketch.desktop.ui.adwaita

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import ketch.desktop.ui.installedFamilies
import ketch.desktop.ui.systemFamily
import pyrlyn.adwaita.AdwaitaFonts
import pyrlyn.adwaita.AdwaitaTheme

/**
 * The first family of each list that this machine has; a machine with none of them keeps Compose's defaults. [density]
 * is the screen's, for [fittedToPixels].
 */
fun adwaitaFonts(
    density: Float,
    families: Set<String> = installedFamilies(),
): AdwaitaFonts {
    val standard = AdwaitaFonts()
    return AdwaitaFonts(
        text = systemFamily(TEXT, families) ?: standard.text,
        mono = systemFamily(MONO, families) ?: standard.mono,
        platform = fittedToPixels(density),
    )
}

/**
 * Glyphs on whole pixels, so a stem falls the same way each time instead of smearing over two pixels. It takes about 3%
 * more width, and only a screen with few pixels to the letter gains from it.
 */
@OptIn(ExperimentalTextApi::class)
internal fun fittedToPixels(density: Float) =
    if (density >= DENSE_SCREEN) {
        null
    } else {
        val settings = FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.Slight, subpixelPositioning = false, autoHintingForced = false)
        PlatformTextStyle(null, PlatformParagraphStyle(settings))
    }

@Composable
fun KetchAdwaita(content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val fonts = remember(density) { adwaitaFonts(density) }
    AdwaitaTheme(fonts = fonts, content = content)
}

private val TEXT = listOf("Inter", "Inter Variable", "Adwaita Sans", "Cantarell", "Ubuntu Sans", "Ubuntu", "Noto Sans")
private val MONO = listOf("JetBrains Mono", "Adwaita Mono", "Ubuntu Sans Mono", "Ubuntu Mono", "Noto Sans Mono", "DejaVu Sans Mono")
private const val DENSE_SCREEN = 2f
