package ketch.core.platform

import ketch.core.model.Os
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxHostTest {
    @Test
    fun hostIsLinux() {
        assertEquals("linux", host().id)
    }

    @Test
    fun idAndTargetAreLinux() {
        val p = LinuxPlatform()
        assertEquals("linux", p.id)
        assertEquals(Os.Linux, p.target().os)
    }
}
