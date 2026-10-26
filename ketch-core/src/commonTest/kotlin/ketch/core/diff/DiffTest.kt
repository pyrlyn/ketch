package ketch.core.diff

import kotlin.test.Test
import kotlin.test.assertEquals

class DiffTest {
    /** `line01` through `lineNN`, newline-joined, for gap-size tests. */
    private fun numbered(count: Int) = (1..count).joinToString("\n") { "line" + it.toString().padStart(2, '0') }

    @Test
    fun identicalTextsDiffToAnEmptyString() {
        val text = "[package]\nname = \"ripgrep\"\n"
        assertEquals("", unified(text, text))
    }

    @Test
    fun emptyTextsDiffToAnEmptyString() = assertEquals("", unified("", ""))

    @Test
    fun aMissingTrailingNewlineIsNotAChange() = assertEquals("", unified("[package]\nname = \"rg\"", "[package]\nname = \"rg\"\n"))

    @Test
    fun anEmptyOldTextIsAllInsertions() = assertEquals("@@ -0,0 +1,2 @@\n+alpha\n+beta\n", unified("", "alpha\nbeta\n"))

    @Test
    fun anEmptyNewTextIsAllDeletions() = assertEquals("@@ -1,2 +0,0 @@\n-alpha\n-beta\n", unified("alpha\nbeta\n", ""))

    @Test
    fun aPureInsertionShowsAsOneAddedLine() = assertEquals("@@ -1,3 +1,4 @@\n alpha\n beta\n+NEW\n gamma\n", unified("alpha\nbeta\ngamma\n", "alpha\nbeta\nNEW\ngamma\n"))

    @Test
    fun aPureDeletionShowsAsOneRemovedLine() = assertEquals("@@ -1,4 +1,3 @@\n alpha\n beta\n-OLD\n gamma\n", unified("alpha\nbeta\nOLD\ngamma\n", "alpha\nbeta\ngamma\n"))

    @Test
    fun aReplacementShowsTheDeletionBeforeTheInsertion() = assertEquals("@@ -1,3 +1,3 @@\n alpha\n-beta\n+BETA\n gamma\n", unified("alpha\nbeta\ngamma\n", "alpha\nBETA\ngamma\n"))

    @Test
    fun nearbyChangesShareOneHunk() {
        val old = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n"
        val new = "one\nTWO\nthree\nfour\nFIVE\nsix\nseven\neight\nnine\nten\n"
        assertEquals(
            "@@ -1,8 +1,8 @@\n one\n-two\n+TWO\n three\n four\n-five\n+FIVE\n six\n seven\n eight\n",
            unified(old, new),
        )
    }

    @Test
    fun distantChangesProduceTwoHunksWithTheirOwnCounts() {
        val old = numbered(16)
        val new = old.replace("line01", "LINE01").replace("line16", "LINE16")
        assertEquals(
            "@@ -1,4 +1,4 @@\n-line01\n+LINE01\n line02\n line03\n line04\n" +
                "@@ -13,4 +13,4 @@\n line13\n line14\n line15\n-line16\n+LINE16\n",
            unified(old, new),
        )
    }

    @Test
    fun contextNeverExceedsThreeLinesPerSide() {
        val old = numbered(12)
        val lines = old.lines().toMutableList()
        lines.add(6, "INSERTED")
        assertEquals(
            "@@ -4,6 +4,7 @@\n line04\n line05\n line06\n+INSERTED\n line07\n line08\n line09\n",
            unified(old, lines.joinToString("\n")),
        )
    }
}
