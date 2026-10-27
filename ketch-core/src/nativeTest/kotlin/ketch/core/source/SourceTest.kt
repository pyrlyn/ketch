package ketch.core.source

import ketch.core.error.KetchError
import ketch.core.model.Release
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun release(
    tag: String,
    prerelease: Boolean = false,
    draft: Boolean = false,
) = Release(Version.parse(tag), tag, prerelease, draft)

class SourceTest {
    private val latest = VersionSpec.Latest

    @Test
    fun latestPrefersHighestStable() {
        val releases = listOf(release("v1.2.0"), release("v2.0.0-rc.1", true), release("v1.10.0"))
        assertEquals("v1.10.0", pick("x", releases, latest, ListOpts()).tag)
    }

    @Test
    fun latestUsesPrereleaseWhenAsked() {
        val releases = listOf(release("v1.2.0"), release("v2.0.0-rc.1", true))
        assertEquals("v2.0.0-rc.1", pick("x", releases, latest, ListOpts(includePrerelease = true)).tag)
    }

    @Test
    fun latestNeverPicksATagThatIsNotAVersionOverOneThatIs() {
        val releases = listOf(release("v0.8.1"), release("desktop-v1.0.0"), release("desktop-appcast", true))
        val traced = selectRelease("x", releases, latest, ListOpts(includePrerelease = true))
        assertEquals("v0.8.1", traced.selected.tag)
        assertEquals(
            listOf(
                RejectedRelease("desktop-v1.0.0", "desktop-v1.0.0", "not a version"),
                RejectedRelease("desktop-appcast", "desktop-appcast", "not a version"),
            ),
            traced.rejected,
        )
    }

    @Test
    fun latestStillPicksAmongTagsNoneOfWhichIsAVersion() {
        assertEquals("stable", pick("x", listOf(release("nightly"), release("stable")), latest, ListOpts()).tag)
    }

    @Test
    fun latestFallsBackToPrereleaseWhenNoStableExists() {
        assertEquals("v0.1.0-alpha", pick("x", listOf(release("v0.1.0-alpha", true)), latest, ListOpts()).tag)
    }

    @Test
    fun exactMatchesWithOrWithoutVPrefix() {
        val releases = listOf(release("v1.2.0"))
        assertEquals("v1.2.0", pick("x", releases, VersionSpec.Exact("1.2.0"), ListOpts()).tag)
        assertFailsWith<KetchError.NoRelease> { pick("x", releases, VersionSpec.Exact("9.9.9"), ListOpts()) }
    }

    @Test
    fun draftsAreNeverSelected() {
        val releases = listOf(release("v3.0.0", draft = true), release("v1.0.0"))
        assertEquals("v1.0.0", pick("x", releases, latest, ListOpts()).tag)
    }

    @Test
    fun aDraftIsSelectedWhenDraftsAreAskedFor() {
        val got = pick("x", listOf(release("v3.0.0", draft = true), release("v1.0.0")), latest, ListOpts(includeDraft = true))
        assertEquals("v3.0.0", got.tag)
        assertTrue(got.draft)
    }

    @Test
    fun anExactTagNamesADraftOnlyWhenDraftsAreAskedFor() {
        val draft = release("v3.0.0", draft = true)
        val want = VersionSpec.Exact("v3.0.0")
        assertFailsWith<KetchError> { pick("x", listOf(draft), want, ListOpts()) }
        assertEquals("v3.0.0", pick("x", listOf(draft), want, ListOpts(includeDraft = true)).tag)
    }

    @Test
    fun selectReleaseRecordsPrereleasesAndAgreesWithPick() {
        val releases = listOf(release("v1.2.0"), release("v2.0.0-rc.1", true), release("v1.10.0"))
        val traced = selectRelease("x", releases, latest, ListOpts())
        assertEquals(pick("x", releases, latest, ListOpts()).tag, traced.selected.tag)
        assertEquals(
            listOf(
                RejectedRelease("v2.0.0-rc.1", "v2.0.0-rc.1", "prerelease"),
                RejectedRelease("v1.2.0", "v1.2.0", "not the highest version"),
            ),
            traced.rejected,
        )
    }

    @Test
    fun selectReleaseNeverSelectsADraft() {
        val traced = selectRelease("x", listOf(release("v3.0.0", draft = true), release("v1.0.0")), latest, ListOpts())
        assertEquals("v1.0.0", traced.selected.tag)
        assertEquals("draft", traced.rejected[0].reason)
    }
}
