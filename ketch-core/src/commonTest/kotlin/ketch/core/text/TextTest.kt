package ketch.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun formatsByteCounts() {
        assertEquals("512 B", bytes(512))
        assertEquals("1.0 KiB", bytes(1024))
        assertEquals("1.5 KiB", bytes(1536))
        assertEquals("5.0 MiB", bytes(5L * 1024 * 1024))
    }

    @Test
    fun truncatesOnCharBoundaries() {
        assertEquals("abcdef", truncate("abcdef", 10))
        assertEquals("abc…", truncate("abcdef", 4))
        // Multi-byte input must not split a character.
        assertEquals("ünïcö…", truncate("ünïcödé-package", 6))
    }

    @Test
    fun linesSplitAsRustDoes() {
        assertEquals(emptyList(), rustLines(""))
        assertEquals(listOf("a"), rustLines("a\n"))
        assertEquals(listOf("a", "", "b"), rustLines("a\r\n\nb"))
    }

    @Test
    fun trimsOnUnicodeWhiteSpaceAsRustDoes() {
        assertEquals("a", "\u0085a\u3000".rustTrim())
        // U+001F is a control character, not white space, whatever Kotlin's own trim says.
        assertEquals("\u001fa", "\u001fa ".rustTrim())
    }

    @Test
    fun escapesInvisibleCharactersAsRustsEscapeDebug() {
        assertEquals("safe\\u{202e}sudo", escapeDebug("safe\u202esudo"))
        assertEquals("a\\tb\\n\\0\\\"", escapeDebug("a\tb\n\u0000\""))
        assertEquals("ünï cö", escapeDebug("ünï cö"))
        assertEquals("\\u{a0}", escapeDebug("\u00a0"))
    }

    @Test
    fun foldsOnlyAsciiCase() {
        assertTrue("Ketch".eqIgnoreAsciiCase("kETCH"))
        assertFalse("\u0130".eqIgnoreAsciiCase("i"))
    }
}
