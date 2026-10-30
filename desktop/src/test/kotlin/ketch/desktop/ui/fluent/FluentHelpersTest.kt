package ketch.desktop.ui.fluent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import ketch.desktop.settings.AppSettings
import ketch.desktop.settings.MemoryKeyValues
import ketch.desktop.settings.Prefs
import ketch.desktop.ui.Appearance
import ketch.desktop.ui.Look
import ketch.desktop.ui.checksumWords
import ketch.desktop.ui.fileName
import ketch.desktop.ui.linkedTo
import ketch.desktop.ui.lookFor
import ketch.desktop.ui.tilde
import pyrlyn.fluent.FluentColors
import pyrlyn.fluent.FluentFonts
import pyrlyn.fluent.Presets
import pyrlyn.fluent.WindowMaterial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FluentHelpersTest {
    @Test
    fun eachSystemWearsItsLook() {
        assertEquals(Look.Fluent, lookFor(null, "Windows 11", highContrast = false))
        assertEquals(Look.Glass, lookFor(null, "Mac OS X", highContrast = false))
        assertEquals(Look.Adwaita, lookFor(null, "Linux", highContrast = false))
        assertEquals(Look.Material, lookFor(null, "FreeBSD", highContrast = false))
    }

    @Test
    fun highContrastKeepsMaterial() {
        assertEquals(Look.Material, lookFor(null, "Windows 11", highContrast = true))
        assertEquals(Look.Material, lookFor(null, "Linux", highContrast = true))
    }

    @Test
    fun aNamedLookIsWornAnywhereAndAnUnknownOneIsIgnored() {
        assertEquals(Look.Fluent, lookFor("fluent", "Mac OS X", highContrast = false))
        assertEquals(Look.Adwaita, lookFor("adwaita", "Mac OS X", highContrast = false))
        assertEquals(Look.Glass, lookFor("nonsense", "Mac OS X", highContrast = false))
    }

    @Test
    fun windowsPathsShorten() {
        assertEquals("~\\.ketch\\bin\\uv.exe", tilde("C:\\Users\\me\\.ketch\\bin\\uv.exe", "C:\\Users\\me"))
        assertEquals("C:\\Users\\menace\\x", tilde("C:\\Users\\menace\\x", "C:\\Users\\me"))
        assertEquals("uv.exe", fileName("C:\\Users\\me\\.ketch\\bin\\uv.exe"))
        assertEquals("~\\bin\\uv.exe, uvx.exe", linkedTo(listOf("C:\\Users\\me\\bin\\uv.exe", "C:\\Users\\me\\bin\\uvx.exe"), "C:\\Users\\me"))
    }

    @Test
    fun eachChecksumPolicyHasItsWords() {
        assertEquals("checked against the published sha256", checksumWords("published-digest"))
        assertEquals("refused unless a checksum is published", checksumWords("require"))
        assertEquals("no published checksum: the first download is trusted", checksumWords("trust-on-first-use"))
        assertEquals("nothing to check yet", checksumWords("unknown"))
    }

    @Test
    fun theStoredMaterialIsWorn() {
        assertEquals(WindowMaterial.Mica, fluentColors(Appearance.Light, Prefs(material = Prefs.MICA)).material)
        assertEquals(WindowMaterial.Transparent, fluentColors(Appearance.Dark, Prefs(material = Prefs.TRANSPARENT)).material)
    }

    @Test
    fun anUnknownMaterialIsTheStandardOne() = assertEquals(WindowMaterial.Acrylic, fluentColors(Appearance.Light, Prefs(material = "stone")).material)

    @Test
    fun aPresetNameAndAStoredColourBothTint() {
        val mint = Presets.tints.first { it.stored == "mint" }
        assertEquals(mint.color, fluentColors(Appearance.Light, Prefs(tint = "mint")).tint)
        assertEquals(Color(0xFF102030), fluentColors(Appearance.Light, Prefs(tint = "#102030")).tint)
        assertNull(fluentColors(Appearance.Light, Prefs()).tint)
    }

    @Test
    fun theChosenAccentReplacesTheStandardOne() {
        val standard = fluentColors(Appearance.Dark, Prefs())
        val purple = Presets.accents.first { it.stored == "purple" }
        assertEquals(FluentColors.Dark.accent, standard.accent)
        assertNotEquals(standard.accent, fluentColors(Appearance.Dark, Prefs(accent = "purple")).accent)
        assertEquals(purple.color, fluentColors(Appearance.Dark, Prefs(accent = "purple")).accent)
    }

    @Test
    fun theWashComesFromThePreferences() = assertEquals(0.05f, fluentColors(Appearance.Light, Prefs(wash = 0.05)).wash)

    @Test
    fun fontsFallBackInOrder() {
        assertEquals(FontFamily.Default, FluentFonts().text)
        assertEquals(FluentFonts(), fluentFonts(emptySet()))
        assertNotEquals(FluentFonts().mono, fluentFonts(setOf("Consolas")).mono)
    }

    @Test
    fun theMaterialIsSavedAndReset() {
        val values = MemoryKeyValues()
        AppSettings(values).update { it.copy(material = Prefs.TRANSPARENT) }
        assertEquals(Prefs.TRANSPARENT, AppSettings(values).state.value.material)
        assertEquals(Prefs.ACRYLIC, Prefs(material = Prefs.TRANSPARENT).standardLook().material)
    }
}
