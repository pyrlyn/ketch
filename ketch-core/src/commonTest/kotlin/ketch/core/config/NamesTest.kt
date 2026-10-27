package ketch.core.config

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun windowsDeviceNamesAndTrailingDotsAreUnusableThere() {
        val unusable =
            listOf(
                "nul",
                "NUL",
                "con",
                "Aux",
                "prn",
                "com1",
                "COM9",
                "lpt1",
                "LPT0",
                "com¹",
                "nul.txt",
                "con.tar.gz",
                "NUL .txt",
                "conin$",
                "CONOUT$",
                "foo.",
                "foo ",
                "a<b",
                "a>b",
                "a\"b",
                "a|b",
                "a?b",
                "a*b",
                "a:b",
                "a\\b",
                "a\tb",
            )
        for (name in unusable) assertTrue(windowsUnusableComponent(name), name)
        for (name in listOf("console", "nullable", "com10", "lpt", "aux_", "_nul", "foo.txt", ".hidden")) {
            assertFalse(windowsUnusableComponent(name), name)
        }
    }

    @Test
    fun sanitizingForWindowsNeverYieldsAnUnusableName() {
        for (raw in listOf("nul", "CON.1", "1.0|beta", "v1*", "a<b>", "lpt²", "x. ", "aux  ")) {
            val clean = sanitizeComponentFor(raw, true)
            assertFalse(windowsUnusableComponent(clean), "$raw -> $clean")
        }
        assertEquals("_nul", sanitizeComponentFor("nul", true))
        assertEquals("1.0-beta", sanitizeComponentFor("1.0|beta", true))
        // Elsewhere those are ordinary names and stay as they were.
        assertEquals("nul", sanitizeComponentFor("nul", false))
        assertEquals("1.0|beta", sanitizeComponentFor("1.0|beta", false))
    }

    @Test
    fun onlyOwnerRepoIsAcceptedAsARepository() {
        val want = "pyrlyn/ketch-registry"
        assertEquals(want, validateRepo("registry", want))
        assertEquals(want, validateRepo("registry", "github:pyrlyn/ketch-registry"))
        for (bad in listOf("", "pyrlyn", "a/b/c", "../etc", "a/../b", "o/r?x=1", "http://x/y")) {
            assertFailsWith<KetchError.Config>(bad) { validateRepo("registry", bad) }
        }
    }

    @Test
    fun sanitizesPathComponents() {
        assertEquals("v1.2.3", sanitizeComponent("v1.2.3"))
        assertEquals("release-1.2", sanitizeComponent("release/1.2"))
        assertEquals("etc", sanitizeComponent("../../etc"))
        assertEquals("unknown", sanitizeComponent(".."))
        assertEquals("unknown", sanitizeComponent(""))
        assertEquals("safe-sudo", sanitizeComponent("safe‮sudo"))
    }

    @Test
    fun currentRepoRenamesOnlyTheRepositoriesThatMoved() {
        assertEquals(SELF_REPO, currentRepo("listepo/ketch"))
        assertEquals(SELF_REPO, currentRepo("LISTEPO/KETCH"))
        assertEquals(REGISTRY_REPO, currentRepo("listepo/ketch-registry"))
        assertEquals(SELF_REPO, currentRepo(SELF_REPO))
        assertEquals("listepo/swarfr", currentRepo("listepo/swarfr"))
    }
}
