package ketch.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsHostTest {
    @Test
    fun hostIsWindows() {
        assertEquals("windows", host().id)
    }
}
