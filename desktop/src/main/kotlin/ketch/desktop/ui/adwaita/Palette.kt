// The Linux look's fonts. The look is dark whatever the desktop is, as its design is: everything else comes from the package.
package ketch.desktop.ui.adwaita

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ketch.desktop.ui.installedFamilies
import ketch.desktop.ui.systemFamily
import pyrlyn.adwaita.AdwaitaFonts
import pyrlyn.adwaita.AdwaitaTheme

/** The first family of each list that this machine has; a machine with none of them keeps Compose's defaults. */
fun adwaitaFonts(families: Set<String> = installedFamilies()): AdwaitaFonts {
    val standard = AdwaitaFonts()
    return AdwaitaFonts(
        text = systemFamily(TEXT, families) ?: standard.text,
        mono = systemFamily(MONO, families) ?: standard.mono,
    )
}

@Composable
fun KetchAdwaita(content: @Composable () -> Unit) {
    val fonts = remember { adwaitaFonts() }
    AdwaitaTheme(fonts = fonts, content = content)
}

private val TEXT = listOf("Inter", "Inter Variable", "Adwaita Sans", "Cantarell", "Ubuntu Sans", "Ubuntu", "Noto Sans")
private val MONO = listOf("JetBrains Mono", "Adwaita Mono", "Ubuntu Sans Mono", "Ubuntu Mono", "Noto Sans Mono", "DejaVu Sans Mono")
