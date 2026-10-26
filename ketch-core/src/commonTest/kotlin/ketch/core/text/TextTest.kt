package ketch.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
