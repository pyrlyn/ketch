package ketch.serve

import ketch.core.changelog.Entry
import ketch.core.changelog.Origin
import ketch.core.install.InstalledBuild
import ketch.core.listing.Found
import ketch.core.listing.Latest
import ketch.core.listing.Local
import ketch.core.listing.Row
import ketch.core.listing.merge
import ketch.core.model.InstalledPackage
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.Release
import ketch.core.model.RetainedVersion
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.host
import ketch.core.platform.DoctorCheck
import ketch.wire.Changelog
import ketch.wire.ChangelogSource
import ketch.wire.Check
import ketch.wire.CheckOutcome
import ketch.wire.Package
import ketch.wire.Upgrade
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsTest {
    private fun installed() =
        InstalledPackage(
            name = "rg",
            version = Version.parse("14.1.0"),
            source = PackageRef.github("BurntSushi/ripgrep"),
            tag = "14.1.0",
            target = TargetSpec.host(),
            assetName = "rg.tar.gz",
            sha256 = "0".repeat(64),
            checksumVerified = true,
            installedAt = 1_700_000_000,
            prefix = Path("/k/store/rg/14.1.0"),
            links = listOf(LinkRecord(Path("/k/bin/rg"), Path("/k/store/rg/14.1.0/rg"), LinkKind.Symlink)),
            pinned = true,
            origin = ManifestOrigin.Inferred,
            retained =
                listOf(
                    RetainedVersion(
                        version = Version.parse("14.0.0"),
                        prefix = Path("/k/store/rg/14.0.0"),
                        sha256 = "1".repeat(64),
                        checksumVerified = true,
                        tag = "14.0.0",
                        assetName = "rg.tar.gz",
                        target = TargetSpec.host(),
                    ),
                ),
        )

    @Test
    fun anInstalledPackageCrossesAsStrings() {
        assertEquals(
            Package(
                name = "rg",
                version = "14.1.0",
                tag = "14.1.0",
                source = "github:BurntSushi/ripgrep",
                pinned = true,
                retained = listOf("14.0.0"),
                installedAt = 1_700_000_000,
                prefix = "/k/store/rg/14.1.0",
                binaries = listOf("/k/bin/rg"),
                trust = "checksum",
            ),
            installed().toWire(),
        )
    }

    private fun row(
        pinned: Boolean,
        latest: String,
    ): Row {
        val pkg = installed().copy(version = Version.parse("14.0.0"), tag = "14.0.0", pinned = pinned)
        val local = Local(pkg.name, pkg.source, InstalledBuild.of(pkg), pinned, emptyList(), prerelease = false, draft = false)
        val rows = merge(listOf(local), emptyList())
        rows[0].latest = Latest.Known(Found.of(Release(Version.parse(latest), latest), false))
        return rows[0]
    }

    @Test
    fun aNewerReleaseIsAnUpgradeWithItsTag() {
        assertEquals(Upgrade("rg", "14.0.0", "14.1.0", "14.1.0", pinned = false, heldBy = null), upgradeOf(row(false, "14.1.0")))
    }

    @Test
    fun aPinnedPackageWithANewerReleaseIsAnUpgradeMarkedHeld() {
        val held = checkNotNull(upgradeOf(row(true, "14.1.0")))
        assertTrue(held.pinned)
        assertEquals("14.1.0", held.latest)
        assertNull(held.heldBy)
    }

    @Test
    fun aCurrentPackageIsNoUpgradePinnedOrNot() {
        assertNull(upgradeOf(row(false, "14.0.0")))
        assertNull(upgradeOf(row(true, "14.0.0")))
    }

    @Test
    fun doctorChecksKeepTheirOutcomeAndLoseControlCharacters() {
        assertEquals(
            Check("links", CheckOutcome.Warn, "1 broken", "ketch link x"),
            DoctorCheck.warn("links", "1 broken\u001b", "ketch link x").toWire(),
        )
    }

    @Test
    fun aChangelogNamesItsFile() {
        val entry = Entry(Origin.File(Path("/p/CHANGELOG.md")), "## 1.0", "- fixed\u001b")
        assertEquals(
            Changelog("rg", "1.0", ChangelogSource.File("/p/CHANGELOG.md"), "## 1.0", "- fixed"),
            changelog("rg", "1.0", entry),
        )
    }
}
