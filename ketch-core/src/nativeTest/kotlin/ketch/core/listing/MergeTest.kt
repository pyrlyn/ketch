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

class MergeTest {
    @Test
    fun mergingInstalledOnlyListsEachInstalledPackage() {
        val rows = merge(listOf(local("bravo", "1.0.0"), local("alpha", "2.0.0")), emptyList())
        assertEquals(listOf("alpha", "bravo"), names(rows))
        assertTrue(rows.all { it.local != null && it.available == null })
    }

    @Test
    fun mergingAvailableOnlyListsEachRegistryPackageUninstalled() {
        val rows = merge(emptyList(), listOf(offer("bravo"), offer("alpha")))
        assertEquals(listOf("alpha", "bravo"), names(rows))
        assertTrue(rows.all { it.local == null && it.available != null })
    }

    @Test
    fun mergingAnInstalledRegistryPackageGivesOneRowWithBothHalves() {
        val rows = merge(listOf(local("alpha", "1.0.0")), listOf(offer("alpha"), offer("bravo")))
        assertEquals(listOf("alpha", "bravo"), names(rows))
        assertTrue(rows[0].local != null && rows[0].available != null)
        assertNull(rows[1].local)
    }

    @Test
    fun mergingMatchesARenamedPackageByItsSource() {
        val renamed = local("rg", "1.0.0").copy(source = PackageRef.github("BurntSushi/ripgrep"))
        val curated = offer("ripgrep").copy(source = PackageRef.github("burntsushi/ripgrep"))
        val rows = merge(listOf(renamed), listOf(curated))
        assertEquals(1, rows.size)
        assertTrue(rows[0].local != null && rows[0].available != null)
    }

    @Test
    fun anInstalledPackageMissingFromTheRegistryIsStillListedWithItsOwnSource() {
        val direct = local("gamma", "1.0.0").copy(source = PackageRef.github("owner/gamma"))
        val rows = merge(listOf(direct), listOf(offer("alpha")))
        assertEquals(listOf("alpha", "gamma"), names(rows))
        val gamma = rows[1]
        assertNull(gamma.available)
        assertEquals(PackageRef.github("owner/gamma"), gamma.source())
        assertEquals(Lookup(PackageRef.github("owner/gamma"), false, false, null), gamma.lookup())
    }

    @Test
    fun aPackageInstalledFromALocalPathIsNotLookedUp() {
        val rows = merge(listOf(local("delta", "1.0.0").copy(source = PackageRef("local", "/tmp/delta"))), emptyList())
        assertNull(rows[0].lookup())
        assertEquals("", rows[0].latestCell())
    }

    @Test
    fun mergingKeepsAPinnedPackagePinned() {
        val rows = merge(listOf(local("alpha", "1.0.0").copy(pinned = true)), listOf(offer("alpha")))
        rows[0].latest = Latest.Known(found("2.0.0"))
        assertEquals("1.0.0 (pinned)", rows[0].local?.cell())
        assertFalse(rows[0].updateAvailable())
        assertEquals("2.0.0", rows[0].latestCell())
        assertNull(updateFooter(rows))
    }

    @Test
    fun theInstalledLookupWinsOverTheRegistryOne() {
        val installed = local("alpha", "1.0.0").copy(prerelease = true, source = PackageRef("test", "alpha-fork"))
        val rows = merge(listOf(installed), listOf(offer("alpha")))
        assertEquals(Lookup(PackageRef("test", "alpha-fork"), true, false, null), rows[0].lookup())
    }

    @Test
    fun theInstalledCellNamesAChannelOtherThanStable() {
        val pkg = local("alpha", "1.0.0-rc.1")
        assertEquals("1.0.0-rc.1", pkg.cell())
        val pre = pkg.copy(build = pkg.build.copy(channel = Channel.Pre), pinned = true)
        assertEquals("1.0.0-rc.1 (pre) (pinned)", pre.cell())
    }

    @Test
    fun askingForPrereleasesWidensInstalledAndOfferedRowsAlike() {
        val rows = merge(listOf(local("alpha", "1.0.0")), listOf(offer("bravo")))
        includePrereleases(rows)
        assertTrue(rows.all { it.lookup()?.prerelease == true })
        rows[0].latest = Latest.Known(found("1.1.0-rc.1"))
        assertTrue(rows[0].updateAvailable())
    }

    @Test
    fun theFooterCountsUpdatesAndNamesTheCommand() {
        val rows = merge(listOf(local("alpha", "1.0.0"), local("bravo", "1.0.0")), listOf(offer("charlie")))
        rows[0].latest = Latest.Known(found("2.0.0"))
        rows[1].latest = Latest.Known(found("1.0.0"))
        rows[2].latest = Latest.Known(found("5.0.0"))
        assertEquals("1 update available: ketch upgrade alpha", updateFooter(rows))
        rows[1].latest = Latest.Known(found("1.1.0"))
        assertEquals("2 updates available: ketch upgrade alpha bravo", updateFooter(rows))
    }

    @Test
    fun theUnreachableNoteNamesEveryQuestionMark() {
        val rows = merge(listOf(local("alpha", "1.0.0")), listOf(offer("bravo")))
        assertNull(unreachableNote(rows))
        rows.forEach { it.latest = Latest.Unreachable }
        assertEquals("?", rows[0].latestCell())
        assertEquals("? means the latest release could not be checked: alpha, bravo", unreachableNote(rows))
    }

    @Test
    fun remoteDescriptionsAreCutToTheWidthAndFiltered() {
        val long = offer("alpha").copy(description = "a very\nlong \u001b[31mdescription that goes on")
        val rows = listOf(Row.offered(long))
        rows[0].latest = Latest.Known(found("1.0.0"))
        // package (7) + latest (6) + 4 separators leaves 13 of 30.
        val cell = remoteCells(rows, 30)[0][2]
        assertEquals(13, cell.length, cell)
        assertTrue(cell.endsWith('…'))
        assertFalse('\u001b' in cell)
        assertFalse('\n' in cell)
        assertEquals("a very long [31mdescription that goes on", remoteCells(rows, null)[0][2])
    }
}
