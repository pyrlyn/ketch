package ketch.core.install

import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.model.Channel
import ketch.core.model.Checksum
import ketch.core.model.InstalledPackage
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.Version
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpgradeTest {
    private val store = Path("/store/tool")

    private fun upgradeWith(
        pkg: InstalledPackage,
        latest: Release,
    ): Pair<Boolean, List<String>> {
        val warns = Warns()
        val upgrade = isUpgrade(Report(warns), InstalledBuild.of(pkg), latest, upgradeOpts(pkg, prerelease = false, draft = false))
        return upgrade to warns.all
    }

    private fun <T> withConfig(body: (Config) -> T): T = withTempDir { body(Config.load(Path(it, "ketch"), Report.silent())) }

    @Test
    fun aPackageOnThePreChannelLooksForPrereleasesWithoutPre() {
        val pkg = installed("tool", "1.0.0", store)
        assertFalse(installedListOpts(pkg, false).includePrerelease)
        assertTrue(installedListOpts(pkg.copy(channel = Channel.Pre), false).includePrerelease)
    }

    @Test
    fun anUpgradeCarriesTheChannelForward(): Unit =
        withConfig { cfg ->
            val pkg = installed("tool", "1.0.0", store)
            assertFalse(upgradeRequest(cfg, pkg, "v1.1.0", null, Cancel()).prerelease)
            val pre = upgradeRequest(cfg, pkg.copy(channel = Channel.Pre), "v1.1.0", null, Cancel())
            assertTrue(pre.prerelease, "a stable tag must not take a pre package off pre")
        }

    @Test
    fun aPackageOnTheDraftChannelLooksForDraftsAndPrereleases() {
        val pkg = installed("tool", "1.0.0", store)
        assertFalse(installedListOpts(pkg, false).includeDraft)
        val opts = installedListOpts(pkg.copy(channel = Channel.Draft), false)
        assertTrue(opts.includeDraft && opts.includePrerelease)
    }

    @Test
    fun anUpgradeKeepsADraftPackageOnDrafts(): Unit =
        withConfig { cfg ->
            val pkg = installed("tool", "1.0.0", store)
            assertFalse(upgradeRequest(cfg, pkg, "v1.1.0", null, Cancel()).draft)
            assertTrue(upgradeRequest(cfg, pkg.copy(channel = Channel.Draft), "v1.1.0", null, Cancel()).draft)
        }

    @Test
    fun onlyAHigherVersionUnderAnotherTagIsAnUpgrade() {
        val pkg = installed("tool", "1.0.0", store)
        assertEquals(true to emptyList(), upgradeWith(pkg, tagged("v1.1.0", false)))
        assertEquals(false to emptyList(), upgradeWith(pkg, tagged("v1.0.0", false)))
        assertEquals(false to emptyList(), upgradeWith(pkg, tagged("v0.9.0", false)))
    }

    @Test
    fun aPublishedDraftIsAnUpgradeUnderTheSameTag() {
        val pkg = installed("tool", "2.0.0", store).copy(channel = Channel.Draft, draft = true)
        assertEquals(false to emptyList(), upgradeWith(pkg, tagged("v2.0.0", true)))
        assertEquals(true to emptyList(), upgradeWith(pkg, tagged("v2.0.0", false)))
        assertEquals(true to emptyList(), upgradeWith(pkg, tagged("v2.1.0", true)))
    }

    @Test
    fun aDeletedDraftIsAWarningAndNotAnUpgrade() {
        val pkg = installed("tool", "2.0.0", store).copy(channel = Channel.Draft, draft = true)
        val (upgrade, said) = upgradeWith(pkg, tagged("v1.0.0", false))
        assertFalse(upgrade)
        assertEquals(1, said.size, "$said")
        assertTrue("draft release v2.0.0" in said[0], "$said")
    }

    /** A `nightly` package as installed: its asset hashed to all zeros, published at [published]. */
    private fun nightly(published: String?) =
        installed("tool", "1.0.0", store).copy(
            version = Version.parse("nightly"),
            tag = "nightly",
            channel = Channel.Dev,
            publishedAt = published,
        )

    /** `nightly` as the source answers now: one asset named [name], with [digest] when the source publishes one. */
    private fun rebuild(
        name: String,
        digest: String?,
        published: String?,
    ) = tagged("nightly", false).copy(
        assets = listOf(asset(name).copy(digest = digest?.let(Checksum::sha256))),
        publishedAt = published,
    )

    @Test
    fun aTagThatIsNotAVersionLandsOnDevWhateverWasOptedInto() {
        val nightly = tagged("nightly", false)
        assertEquals(Channel.Dev, Channel.of(nightly, false, false))
        assertEquals(Channel.Dev, Channel.of(nightly, true, false))
        assertEquals(Channel.Draft, Channel.of(nightly, false, true))
        assertEquals(Channel.Stable, Channel.of(tagged("v1.0.0", false), false, false))
    }

    @Test
    fun theSameDigestUnderTheTagIsTheInstalledBuildWhateverItsDate() {
        val pkg = nightly("2026-10-01T00:00:00Z")
        val same = rebuild("a.tar.gz", "0".repeat(64), "2026-10-09T00:00:00Z")
        assertFalse(rebuilt(InstalledBuild.of(pkg), same))
        assertEquals(false to emptyList(), upgradeWith(pkg, same))
    }

    @Test
    fun anotherDigestUnderTheTagIsANewBuildAndAnUpgrade() {
        val pkg = nightly("2026-10-01T00:00:00Z")
        val moved = rebuild("a.tar.gz", "1".repeat(64), "2026-10-01T00:00:00Z")
        assertTrue(rebuilt(InstalledBuild.of(pkg), moved))
        assertEquals(true to emptyList(), upgradeWith(pkg, moved))
    }

    @Test
    fun withoutADigestThePublicationDateDecides() {
        val pkg = InstalledBuild.of(nightly("2026-10-01T00:00:00Z"))
        assertFalse(rebuilt(pkg, rebuild("a.tar.gz", null, "2026-10-01T00:00:00Z")))
        assertTrue(rebuilt(pkg, rebuild("a.tar.gz", null, "2026-10-09T00:00:00Z")))
    }

    @Test
    fun aMissingDateOnEitherSideIsNoEvidenceOfANewBuild() {
        assertFalse(rebuilt(InstalledBuild.of(nightly(null)), rebuild("a.tar.gz", null, "2026-10-09T00:00:00Z")))
        assertFalse(rebuilt(InstalledBuild.of(nightly("2026-10-01T00:00:00Z")), rebuild("a.tar.gz", null, null)))
    }

    @Test
    fun anAssetRenamedUnderTheTagIsANewBuild() {
        val pkg = InstalledBuild.of(nightly("2026-10-01T00:00:00Z"))
        assertTrue(rebuilt(pkg, rebuild("tool-20261009.tar.gz", null, "2026-10-01T00:00:00Z")))
    }

    @Test
    fun aStablePackageIgnoresBuildsUnderItsOwnTag() {
        val pkg = installed("tool", "1.0.0", store)
        val retagged = tagged("v1.0.0", false).copy(assets = listOf(asset("a.tar.gz").copy(digest = Checksum.sha256("1".repeat(64)))))
        assertEquals(false to emptyList(), upgradeWith(pkg, retagged))
    }

    @Test
    fun aTagTheLockfilePinnedToAnotherBuildIsRefused() {
        val req = InstallRequest(PackageSpec.parse("o/r@nightly"))
        val now = rebuild("a.tar.gz", null, "2026-10-09T00:00:00Z")
        checkPublished(req, "tool", now)
        checkPublished(req.copy(expectedPublished = "2026-10-09T00:00:00Z"), "tool", now)
        val pinned = req.copy(expectedPublished = "2026-10-01T00:00:00Z")
        val err = assertFailsWith<KetchError> { checkPublished(pinned, "tool", now) }.message
        assertTrue("ketch lock" in err, err)
        checkPublished(pinned, "tool", rebuild("a.tar.gz", null, null))
    }
}
