package ketch.core.link

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkTest {
    private val esc = Char(0x1B)

    @Test
    fun aLinkToAPackagePageYieldsItsName() {
        for ((link, name) in listOf(
            "ketch://package/ripgrep" to "ripgrep",
            "ketch://package/ripgrep/" to "ripgrep",
            "KETCH://Package/RipGrep" to "ripgrep",
            "ketch://package/foo.bar_baz-1+x" to "foo.bar_baz-1+x",
        )) {
            assertEquals(name, packageName(link), link)
        }
    }

    @Test
    fun aLinkThatNamesAnActionOtherThanOpeningAPageIsRefused() {
        for (link in listOf(
            "ketch://install/ripgrep",
            "ketch://upgrade/ripgrep",
            "ketch://uninstall/ripgrep",
            "ketch://package/ripgrep?install=1",
            "ketch://package/ripgrep#install",
        )) {
            assertFailsWith<KetchError>(link) { packageName(link) }
        }
    }

    @Test
    fun aMalformedOrHostileLinkIsRefused() {
        val hostile =
            listOf(
                "",
                "https://package/ripgrep",
                "package/ripgrep",
                "ketch:package/ripgrep",
                "ketch://package",
                "ketch://package/",
                "ketch://package/a/b",
                "ketch://package//a",
                "ketch://package/..",
                "ketch://package/../evil",
                "ketch://package/.",
                "ketch://package/.hidden",
                "ketch://package/-rf",
                "ketch://package/evil.",
                "ketch://package/..\\evil",
                "ketch://package/%2e%2e",
                "ketch://package/a%2fb",
                "ketch://user@package/ripgrep",
                "ketch://package:8080/ripgrep",
                "ketch://package/rip grep",
                "ketch://package/rip\ngrep",
                "ketch://package/rip" + Char(0) + "grep",
                "ketch://package/$esc[31mred",
                "ketch://package/safe" + Char(0x202E) + "txt",
                "ketch://package/r" + Char(0x0131) + "pgrep",
                "ketch://package/a;rm",
                "ketch://package/owner/repo",
            )
        for (link in hostile) assertFailsWith<KetchError>("${link.encodeToByteArray().toList()} was accepted") { packageName(link) }
    }

    @Test
    fun anOversizedLinkIsRefusedWithoutEchoingIt() {
        val message = assertFailsWith<KetchError> { packageName("ketch://package/" + "a".repeat(MAX_LEN)) }.message
        assertTrue(message.length < 200, message)
    }

    @Test
    fun anOversizedNameIsRefused() {
        assertFailsWith<KetchError> { packageName("ketch://package/" + "a".repeat(MAX_NAME + 1)) }
    }

    @Test
    fun aRefusalEscapesWhatItQuotes() {
        val message = assertFailsWith<KetchError> { packageName("ketch://package/a$esc[2Jb") }.message
        assertFalse(esc in message, message)
    }
}
