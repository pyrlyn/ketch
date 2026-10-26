package ketch.core

import kotlin.test.Test
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun versionIsSemver() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(VERSION))
    }
}
