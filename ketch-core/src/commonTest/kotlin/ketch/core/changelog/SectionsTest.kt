package ketch.core.changelog

import ketch.core.model.Release
import ketch.core.model.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val KEEP_A_CHANGELOG =
    """
    # Changelog

    ## [Unreleased]

    - something in flight

    ## [1.2.3] - 2024-05-01

    ### Added

    - the thing everybody wanted

    ### Fixed

    - the thing nobody noticed

    ## [1.2.2] - 2024-04-01

    - an older fix
    """.trimIndent() + "\n"

class SectionsTest {
    @Test
    fun aKeepAChangelogSectionStopsAtTheNextRelease() {
        val (heading, body) = assertNotNull(section(KEEP_A_CHANGELOG, "1.2.3"))
        assertEquals("## [1.2.3] - 2024-05-01", heading)
        assertTrue("the thing everybody wanted" in body)
        assertTrue("### Fixed" in body, "sub-headings stay in: $body")
        assertFalse("an older fix" in body, "ran into 1.2.2: $body")
        assertFalse("something in flight" in body)
    }

    @Test
    fun aTagAndItsVersionFindTheSameSection() {
        assertEquals(assertNotNull(section(KEEP_A_CHANGELOG, "1.2.3")).first, assertNotNull(section(KEEP_A_CHANGELOG, "v1.2.3")).first)
    }

    @Test
    fun theLastSectionInAFileRunsToTheEnd() {
        assertEquals("- an older fix", assertNotNull(section(KEEP_A_CHANGELOG, "1.2.2")).second)
    }

    @Test
    fun everyWayPeopleWriteAVersionHeadingIsRecognised() {
        for (heading in listOf(
            "## 1.2.3",
            "## v1.2.3",
            "## [1.2.3]",
            "## [1.2.3] - 2024-05-01",
            "## 1.2.3 (2024-05-01)",
            "## Release 1.2.3",
            "### v1.2.3 — codename",
            "# 1.2.3",
        )) {
            assertNotNull(section("$heading\n\n- a change\n", "1.2.3"), "did not match `$heading`")
        }
    }

    @Test
    fun aVersionThatIsAPrefixOfAnotherIsNotMatched() {
        assertNull(section("## 1.2.30\n\n- not this one\n", "1.2.3"))
    }

    @Test
    fun aVersionNobodyWroteAboutHasNoSection() {
        assertNull(section(KEEP_A_CHANGELOG, "9.9.9"))
        assertNull(section(KEEP_A_CHANGELOG, ""))
    }

    @Test
    fun aDeeperHeadingDoesNotEndASectionButAShallowerOneDoes() {
        val (_, body) = assertNotNull(section("## 1.0.0\n\n### Added\n- a\n\n# 0.9.0\n- old\n", "1.0.0"))
        assertTrue("### Added" in body)
        assertFalse("old" in body)
    }

    @Test
    fun aRunOfHashesThatIsNotAHeadingIsNotTreatedAsOne() {
        assertNull(atxLevel("#tag"))
        assertNull(atxLevel("####### too deep"))
        assertEquals(2, atxLevel("## fine"))
        assertNull(atxLevel("not a heading"))
    }

    @Test
    fun anUnderlinedVersionIsAHeadingAndTheUnderlineIsNotInTheBody() {
        val text = "15.2.0 (2026-07-15)\n===================\nPlatform support:\n\n* a thing\n\n15.1.0 (2026-05-01)\n===================\nolder\n"
        val (heading, body) = assertNotNull(section(text, "15.2.0"))
        assertEquals("15.2.0 (2026-07-15)", heading)
        assertTrue(body.startsWith("Platform support:"), body)
        assertFalse("older" in body, "ran into 15.1.0: $body")
        assertFalse("===" in body, "kept the underline: $body")
    }

    /** `---` is a Setext underline in Markdown, but in a changelog it is nearly always a separator. */
    @Test
    fun aRuleBetweenReleasesNeverSwallowsTheLinesAboveIt() {
        val (_, body) = assertNotNull(section("## 1.2.3\n\n- the change\n\n---\n\n## 1.2.2\n", "1.2.3"))
        assertTrue("- the change" in body, "truncated at the rule: $body")
    }

    @Test
    fun aChangelogCannotDriveTheTerminalItIsPrintedTo() {
        val hostile = "\u001b[2J\u001b]0;pwned\u0007real\rtext‮reversed\u009bm"
        val entry = assertNotNull(fromRelease(hostile))
        assertEquals("[2J]0;pwnedrealtextreversedm", entry.body)
    }

    /** U+202E is the loud one; its neighbours reorder text just as well and are just as invisible. */
    @Test
    fun sanitisingDropsTheInvisibleCharactersThatReorderALine() {
        val tag = "󠁁"
        assertEquals("abcdefg", sanitize("a‎b‏c؜d​e﻿f⁦g$tag"))
    }

    @Test
    fun aHashInsideAFencedBlockIsACommentNotAHeading() {
        val text =
            """
            # Changelog

            ## [1.2.3] - 2024-05-01

            - the real 1.2.3 body

            ```sh
            # 1.2.2 is the one to pin
            ketch install demo@1.2.2
            ```

            ## [1.2.2] - 2024-04-01

            - older
            """.trimIndent() + "\n"
        val (heading, body) = assertNotNull(section(text, "1.2.3"))
        assertEquals("## [1.2.3] - 2024-05-01", heading)
        assertTrue("the real 1.2.3 body" in body, body)
        assertTrue("ketch install demo@1.2.2" in body, body)
        assertFalse("older" in body, "the section ran past 1.2.2: $body")
        val (older, olderBody) = assertNotNull(section(text, "1.2.2"))
        assertEquals("## [1.2.2] - 2024-04-01", older)
        assertEquals("- older", olderBody)
    }

    @Test
    fun sanitisingKeepsTheShapeOfTheProse() {
        assertEquals("## 1.0\n\n\t- a\n- b\n", sanitize("## 1.0\n\n\t- a\r\n- b\n"))
    }

    @Test
    fun aReleaseWithNoNotesProducesNothingRatherThanABlankEntry() {
        assertNull(fromRelease(null))
        assertNull(fromRelease("   \n "))
        assertNotNull(fromRelease("real notes"))
    }

    private fun release(
        version: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = Release(Version.parse(version), "v$version", prerelease, draft, null, "notes for $version")

    private fun versions(kept: List<Release>) = kept.map { it.version.toString() }

    @Test
    fun aRangeExcludesFromIncludesToAndRunsNewestFirst() {
        val releases = listOf(release("1.0.0"), release("1.2.0"), release("1.1.0"), release("1.3.0"))
        assertEquals(listOf("1.2.0", "1.1.0"), versions(between(releases, Version.parse("1.0.0"), Version.parse("v1.2.0"), false)))
    }

    @Test
    fun anOpenRangeReachesEveryRelease() {
        assertEquals(listOf("2.0.0", "1.0.0"), versions(between(listOf(release("1.0.0"), release("2.0.0")), null, null, false)))
    }

    @Test
    fun draftsAndUnaskedForPrereleasesStayOutOfARange() {
        val releases = listOf(release("1.1.0", draft = true), release("1.2.0-rc.1"), release("1.3.0", prerelease = true), release("1.0.1"))
        assertEquals(listOf("1.0.1"), versions(between(releases, null, null, false)))
        assertEquals(listOf("1.3.0", "1.2.0-rc.1", "1.0.1"), versions(between(releases, null, null, true)))
    }
}
