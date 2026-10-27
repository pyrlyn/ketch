package ketch.core.extract

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SafeMemberPathTest {
    @Test
    fun rejectsTraversalEntries() {
        for (raw in listOf("../../etc/passwd", "/etc/passwd", "a/../../b", "")) {
            assertFailsWith<KetchError>(raw) { safeMemberPath(raw) }
        }
    }

    @Test
    fun acceptsAndNormalisesOrdinaryEntries() {
        assertEquals("bin/rg", safeMemberPath("./bin/rg"))
        assertEquals("rg-14/complete/rg.1", safeMemberPath("rg-14/complete/rg.1"))
    }

    @Test
    fun windowsDeviceNamesAndTrailingDotsAreRefusedThereOnly() {
        for (raw in listOf("bin/nul", "bin/NUL.exe", "COM1", "docs/con.txt", "a/foo.", "a/foo /b", "x/a<b")) {
            assertFailsWith<KetchError>(raw) { safeMemberPathFor(raw, true) }
            safeMemberPathFor(raw, false)
        }
        for (raw in listOf("bin/console", "lib/nullable.so", "com10", "./bin/tool.exe")) safeMemberPathFor(raw, true)
    }

    @Test
    fun rejectsWindowsStyleNames() {
        assertFailsWith<KetchError> { safeMemberPath("C:windows") }
        assertFailsWith<KetchError> { safeMemberPath("a\\b") }
    }
}
