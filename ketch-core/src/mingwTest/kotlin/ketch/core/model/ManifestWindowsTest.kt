package ketch.core.model

import kotlin.test.Test
import kotlin.test.assertFails

class ManifestWindowsTest {
    /** A bin or package called `nul` would install as the null device. */
    @Test
    fun windowsDeviceNamesAreNotUsableFileNames() {
        for (name in listOf("nul", "CON", "com1.exe", "tool.", "a|b")) assertFails(name) { usableFileName("binary name", name) }
        usableFileName("binary name", "console")
    }
}
