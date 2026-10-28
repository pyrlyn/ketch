package ketch.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RustComponentsTest {
    @Test
    fun unixPathsSplitOnSlashOnly() {
        assertEquals(listOf("", "a", "b\\c"), rustComponentsFor("/a/./b\\c/", windows = false))
        assertEquals(listOf("a", "b"), rustComponentsFor("a//b", windows = false))
    }

    @Test
    fun aWindowsDriveLeadsAndBothSeparatorsSplit() {
        assertEquals(listOf("C:", "", "Users", "u"), rustComponentsFor("c:\\Users/u", windows = true))
        assertEquals(listOf("C:", "rel"), rustComponentsFor("C:rel", windows = true))
        assertEquals(listOf("", "a"), rustComponentsFor("\\a", windows = true))
    }

    @Test
    fun aUncPrefixHoldsServerAndShare() {
        assertEquals(listOf("\\\\srv\\share", "", "dir"), rustComponentsFor("//srv/share/dir", windows = true))
        assertEquals(listOf("\\\\srv", ""), rustComponentsFor("\\\\srv", windows = true))
    }

    @Test
    fun aVerbatimPathSplitsOnBackslashOnly() {
        assertEquals(listOf("\\\\?\\C:", "", "a/b"), rustComponentsFor("\\\\?\\c:\\a/b", windows = true))
        assertEquals(listOf("\\\\?\\UNC", "", "srv"), rustComponentsFor("\\\\?\\UNC\\srv", windows = true))
    }
}
