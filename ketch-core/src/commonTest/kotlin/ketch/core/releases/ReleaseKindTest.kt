package ketch.core.releases

import ketch.core.model.Release
import ketch.core.model.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseKindTest {
    private fun kind(
        tag: String,
        prerelease: Boolean,
        draft: Boolean,
    ) = ReleaseKind.of(Release(Version.parse(tag), tag, prerelease, draft)).label

    private fun entry(stamp: String?) = Entry("v1", Version.parse("v1"), stamp, ReleaseKind.Stable, false)

    @Test
    fun aPlainVersionIsStable() {
        assertEquals("stable", kind("v1.2.3", false, false))
    }

    @Test
    fun thePrereleaseFlagOrASemverPrereleaseMakesItPre() {
        assertEquals("pre", kind("v1.2.3", true, false))
        assertEquals("pre", kind("v2.0.0-rc.1", false, false))
    }

    @Test
    fun aTagThatIsNotAVersionIsDevEvenWhenFlaggedPrerelease() {
        assertEquals("dev", kind("nightly", false, false))
        assertEquals("dev", kind("nightly", true, false))
    }

    @Test
    fun aDraftIsADraftWhateverElseItIs() {
        assertEquals("draft", kind("v1.2.3", false, true))
        assertEquals("draft", kind("nightly", true, true))
    }

    @Test
    fun theDateIsTheDayOfAWellFormedStamp() {
        assertEquals("2026-10-10", entry("2026-10-10T12:00:00Z").date())
        assertEquals("2026-10-10", entry("2026-10-10").date())
        assertNull(entry("yesterday-ish").date())
        assertNull(entry("2026").date())
        assertNull(entry("\u001b[31m2026-10-1").date())
        assertNull(entry(null).date())
    }

    @Test
    fun aStampCutInsideAMultibyteCharacterIsNotADate() {
        assertNull(entry("2026-10-1é").date())
    }
}
