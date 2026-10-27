package ketch.core.listing

import ketch.core.config.Config
import ketch.core.install.InstalledBuild
import ketch.core.model.Channel
import ketch.core.model.Checksum
import ketch.core.model.PackageRef
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.Version
import ketch.core.model.nowUnix
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    @Test
    fun aNewerReleaseIsAnUpdate() = assertTrue(updateAvailable(local("alpha", "1.0.0"), found("1.1.0")))

    @Test
    fun theSameReleaseIsNotAnUpdate() = assertFalse(updateAvailable(local("alpha", "1.0.0"), found("1.0.0")))

    @Test
    fun theInstalledTagIsNotAnUpdateHoweverItsVersionParses() {
        val odd = found("1.0.1").let { it.copy(release = it.release.copy(tag = "v1.0.0")) }
        assertFalse(updateAvailable(local("alpha", "1.0.0"), odd))
    }

    @Test
    fun anOlderReleaseIsNotAnUpdate() = assertFalse(updateAvailable(local("alpha", "2.0.0"), found("1.9.9")))

    @Test
    fun aPrereleaseIsAnUpdateOnlyWhenPrereleasesAreAllowed() {
        val stable = local("alpha", "1.0.0")
        assertFalse(updateAvailable(stable, found("1.1.0-rc.1")))
        assertTrue(updateAvailable(stable.copy(prerelease = true), found("1.1.0-rc.1")))
    }

    @Test
    fun aStableReleaseSupersedesThePrereleaseItFollows() = assertTrue(updateAvailable(local("alpha", "2.0.0-rc.1"), found("2.0.0")))

    @Test
    fun aPinnedPackageNeverHasAnUpdate() {
        val held = local("alpha", "1.0.0").copy(pinned = true)
        assertFalse(updateAvailable(held, found("9.0.0")))
        assertFalse(updateAvailable(held.copy(prerelease = true), found("9.0.0-rc.1")))
    }

    @Test
    fun aDevBuildRepublishedUnderItsTagIsAnUpdate() {
        val dev = dev("2026-01-01T00:00:00Z")
        assertTrue(updateAvailable(dev, nightly("2026-02-01T00:00:00Z", null)))
        assertFalse(updateAvailable(dev, nightly("2026-01-01T00:00:00Z", null)))
        assertTrue(updateAvailable(dev, nightly("2026-01-01T00:00:00Z", "1".repeat(64))))
        assertFalse(updateAvailable(dev, nightly("2026-02-01T00:00:00Z", "0".repeat(64))))
    }

    @Test
    fun aDraftInstallIsOfferedItsReleaseOncePublished() {
        val base = local("alpha", "2.0.0")
        val held = base.copy(build = base.build.copy(channel = Channel.Draft, draft = true), draft = true, prerelease = true)
        val published = found("2.0.0")
        assertTrue(updateAvailable(held, published))
        assertFalse(updateAvailable(held, Found.of(published.release.copy(draft = true), false)))
        assertFalse(updateAvailable(held, found("1.0.0")))
    }

    @Test
    fun anUnaskedPrereleaseIsNotAnUpdate() {
        val pre = Found.of(found("2.0.0").release.copy(prerelease = true), false)
        val alpha = local("alpha", "1.0.0")
        assertFalse(updateAvailable(alpha, pre))
        assertTrue(updateAvailable(alpha.copy(prerelease = true), pre))
    }
}
