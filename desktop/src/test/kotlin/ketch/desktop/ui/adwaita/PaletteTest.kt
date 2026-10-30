package ketch.desktop.ui.adwaita

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTextApi::class)
class PaletteTest {
    @Test
    fun anOrdinaryScreenPutsGlyphsOnWholePixels() {
        for (density in listOf(1f, 1.25f, 1.5f)) {
            assertEquals(false, fittedToPixels(density)?.paragraphStyle?.fontRasterizationSettings?.subpixelPositioning)
        }
    }

    @Test
    fun aDenseScreenKeepsTheSystemsText() {
        assertNull(fittedToPixels(2f))
        assertNull(fittedToPixels(3f))
    }

    @Test
    fun aMachineWithoutTheFontsKeepsTheDefaultsAndTheFit() {
        val fonts = adwaitaFonts(1f, emptySet())
        assertEquals(FontFamily.Default, fonts.text)
        assertNotEquals(null, fonts.platform)
    }
}
