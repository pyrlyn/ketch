package ketch.core.platform.macos

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.listDir
import fs.readLink
import fs.removeAny
import fs.removeFile
import ketch.core.error.KetchError
import ketch.core.extra.ExtraPlacement
import ketch.core.extract.macos.copyTree
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.model.PackageKind
import ketch.core.platform.Claims
import ketch.core.platform.discoverExecutables
import ketch.core.platform.finalizeStoreSwap
import ketch.core.platform.host
import ketch.core.platform.linkBinary
import ketch.core.platform.placement
import ketch.core.platform.restoreRetiredStore
import ketch.core.platform.symlink
import ketch.core.report.Report
import ketch.core.testing.program
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOsPlatformTest {
    private fun isFile(path: Path) = entryKind(path) == EntryKind.File

    private fun exists(path: Path) = entryKind(path) != null

    @Test
    fun hostIsMacos() {
        assertEquals("macos", host().id)
    }

    @Test
    fun appBundlesAreFoundButTheirHelpersAreNot() {
        withTempDir { root ->
            val app = Path(root, "Thing.app")
            createDirAll(Path(app, "Contents/Updater.app"))
            createDirAll(Path(root, "Extra.app"))

            val found = findAppBundles(root).sortedBy { it.toString() }
            assertEquals(listOf(Path(root, "Extra.app"), app), found)
        }
    }

    @Test
    fun aFailedUpgradeLeavesTheInstalledVersionInPlace() {
        withTempDir { tmp ->
            val store = Path(tmp, "store/tool/1.0")
            createDirAll(store)
            Path(store, "tool").write("the working version")

            // Nothing to move: the version already installed must survive.
            assertFailsWith<KetchError> { moveIntoStore(Path(tmp, "missing"), store) }
            assertEquals("the working version", Path(store, "tool").read())

            val payload = Path(tmp, "payload")
            createDirAll(payload)
            Path(payload, "tool").write("the new version")
            moveIntoStore(payload, store)
            assertEquals("the new version", Path(store, "tool").read())
            assertTrue(exists(Path(tmp, "store/tool/1.0.old")), "the previous version stays until links succeed")
            assertNull(entryKind(Path(tmp, "store/tool/1.0.incoming")))
            restoreRetiredStore(store)
            assertEquals("the working version", Path(store, "tool").read())
            finalizeStoreSwap(store)
            assertNull(entryKind(Path(tmp, "store/tool/1.0.old")))
        }
    }

    @Test
    fun unplaceLeavesAFileTheUserPutWhereALinkWas() {
        withTempDir { tmp ->
            val target = Path(tmp, "store/pkg/1.0/tool")
            createDirAll(target.parent!!)
            target.write("#!/bin/sh\n")

            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val link = Path(bin, "tool")
            val record = LinkRecord(link, target, LinkKind.Symlink, LinkRole.Binary)
            val platform = MacOsPlatform()

            // The link ketch made is ketch's to remove.
            symlink(target, link)
            platform.unplace(listOf(record), Report.silent())
            assertNull(entryKind(link))

            // The user replaced it with a copy of their own: the record is stale, and removing what is there would
            // delete their file.
            link.write("the user's own build")
            platform.unplace(listOf(record), Report.silent())
            assertEquals("the user's own build", link.read(), "a stale record must not authorize deleting the user's file")

            // Already gone is not a failure: uninstall stays idempotent.
            removeFile(link)
            platform.unplace(listOf(record), Report.silent())

            // A copied `.app` whose store copy is gone: same stale-record class.
            val store = Path(tmp, "store/thing/1.0")
            val bundle = Path(store, "Thing.app")
            createDirAll(Path(bundle, "Contents"))
            Path(bundle, "Contents/Info.plist").write("x")
            val apps = Path(tmp, "Applications")
            createDirAll(apps)
            val appLink = Path(apps, "Thing.app")
            copyTree(bundle, appLink)
            val appRecord = LinkRecord(appLink, bundle, LinkKind.CopiedApp, LinkRole.Binary)

            platform.unplace(listOf(appRecord), Report.silent())
            assertNull(entryKind(appLink))

            createDirAll(Path(appLink, "Contents"))
            Path(appLink, "Contents/mine.txt").write("the user's own copy")
            removeAny(store)
            platform.unplace(listOf(appRecord), Report.silent())
            assertTrue(isFile(Path(appLink, "Contents/mine.txt")), "a stale record must not authorize deleting the user's bundle")
        }
    }

    @Test
    fun aBinaryNameAnotherPackageOwnsIsNotTakenOver() {
        withTempDir { tmp ->
            val mine = Path(tmp, "store/mine")
            val theirs = Path(tmp, "store/theirs/1.0")
            createDirAll(Path(mine, "1.0"))
            createDirAll(theirs)
            val target = Path(mine, "1.0/tool")
            target.write("#!/bin/sh\n")
            Path(theirs, "tool").write("#!/bin/sh\n")

            val bin = Path(tmp, "bin")
            createDirAll(bin)
            symlink(Path(theirs, "tool"), Path(bin, "tool"))
            assertFailsWith<KetchError> { linkBinary(target, bin, "tool", mine, emptyList(), Claims()) }
            assertEquals(Path(theirs, "tool"), readLink(Path(bin, "tool")), "the other package must still own its link")

            // An older version of the same package is ours to replace.
            val old = Path(mine, "0.9")
            createDirAll(old)
            Path(old, "tool").write("#!/bin/sh\n")
            removeFile(Path(bin, "tool"))
            symlink(Path(old, "tool"), Path(bin, "tool"))
            val record = linkBinary(target, bin, "tool", mine, emptyList(), Claims())
            assertEquals(target, readLink(record.link))
        }
    }

    @Test
    fun discoverPrefersCapitalBinOverNoisySiblings() {
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            val keep = program(Path(payload, "Bin"), "rg", "#!/bin/sh\necho rg\n")
            program(payload, "noise", "#!/bin/sh\necho noise\n")

            val found = discoverExecutables(MacOsPlatform(), payload, "rg")
            assertEquals(listOf(keep), found, "$found")
        }
    }

    @Test
    fun placeLinksClassifiedExtraPathsAndRecordsThem() {
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            createDirAll(Path(payload, "complete"))
            createDirAll(Path(payload, "doc"))
            Path(payload, "complete/rg.bash").write("# bash\n")
            Path(payload, "doc/rg.1").write(".TH RG 1\n")
            program(payload, "rg")

            val store = Path(tmp, "store/rg/1.0")
            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val manDest = Path(tmp, "man/man1/rg.1")
            val compDest = Path(tmp, "completions/rg")
            val extras =
                listOf(
                    ExtraPlacement("complete/rg.bash", compDest, LinkRole.Completion),
                    ExtraPlacement("doc/rg.1", manDest, LinkRole.Man),
                )
            val plan = placement(payload, store, bin, Path(tmp, "Applications"), extras = extras)
            val links = MacOsPlatform().place(plan)
            assertTrue(links.any { it.role == LinkRole.Man && it.link == manDest }, "$links")
            assertTrue(links.any { it.role == LinkRole.Completion && it.link == compDest }, "$links")
            assertEquals(Path(store, "doc/rg.1"), readLink(manDest))
            assertEquals(Path(store, "complete/rg.bash"), readLink(compDest))

            MacOsPlatform().unplace(links, Report.silent())
            assertNull(entryKind(manDest))
            assertNull(entryKind(compDest))
        }
    }

    @Test
    fun placementChecksAllBinaryDestinationsBeforeReplacingAny() {
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            for (name in listOf("first", "second")) program(payload, name)

            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val external = Path(tmp, "external")
            external.write("#!/bin/sh\n")
            symlink(external, Path(bin, "second"))

            val store = Path(tmp, "store/pkg/1.0")
            val plan = placement(payload, store, bin, Path(tmp, "Applications"), name = "pkg")

            assertFailsWith<KetchError> { preflightDestinations(MacOsPlatform(), plan, store.parent!!) }
            assertNull(entryKind(Path(bin, "first")), "preflight must not create links")
        }
    }

    @Test
    fun placementPreflightChecksThePackageNameForASingleBuildArtifact() {
        withTempDir { tmp ->
            val payload = Path(tmp, "payload")
            program(payload, "tool-macos-arm64")

            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val external = Path(tmp, "external")
            external.write("#!/bin/sh\n")
            symlink(external, Path(bin, "tool"))

            val store = Path(tmp, "store/tool/1.0")
            val plan = placement(payload, store, bin, Path(tmp, "Applications"), name = "tool")

            assertFailsWith<KetchError> { preflightDestinations(MacOsPlatform(), plan, store.parent!!) }
        }
    }

    @Test
    fun anAppKetchDidNotInstallIsNeverDeleted() {
        withTempDir { tmp ->
            val packageDir = Path(tmp, "store/thing")
            val bundle = Path(packageDir, "1.0/Thing.app")
            createDirAll(Path(bundle, "Contents"))
            Path(bundle, "Contents/Info.plist").write("x")

            val apps = Path(tmp, "Applications")
            val existing = Path(apps, "Thing.app")
            createDirAll(existing)
            Path(existing, "mine.txt").write("the user's own copy")

            assertFailsWith<KetchError> { placeApp(bundle, apps, false, packageDir, emptyList(), Claims()) }
            assertTrue(isFile(Path(existing, "mine.txt")), "must not be deleted")

            // A copied bundle leaves no mark on disk; the record we wrote when we placed it is the only thing that
            // makes it ours to replace.
            val recorded = listOf(LinkRecord(existing, bundle, LinkKind.CopiedApp, LinkRole.Binary))
            val claims = Claims()
            placeApp(bundle, apps, false, packageDir, recorded, claims)
            claims.discard()
            assertTrue(isFile(Path(existing, "Contents/Info.plist")))
            assertNull(entryKind(Path(existing, "mine.txt")))
        }
    }

    /** A payload, its store and extras that fail placement after every binary link and app copy is in place. */
    private data class FailingUpgrade(
        val payload: Path,
        val store: Path,
        val extras: List<ExtraPlacement>,
    )

    /**
     * A payload with `tool` and `README`, and an extra whose destination cannot be created because a file sits where
     * its folder should be — so placement fails after every binary link and app copy is in place.
     */
    private fun failingUpgrade(tmp: Path): FailingUpgrade {
        val payload = Path(tmp, "payload")
        program(payload, "tool", "#!/bin/sh\necho 2.0\n")
        Path(payload, "README").write("2.0")
        val blocker = Path(tmp, "blocker")
        blocker.write("not a folder")
        val extras = listOf(ExtraPlacement("README", Path(blocker, "README"), LinkRole.Man))
        return FailingUpgrade(payload, Path(tmp, "store/tool/2.0"), extras)
    }

    @Test
    fun aFailedPlacementPutsTheOldLinksBack() {
        withTempDir { tmp ->
            val oldTarget = Path(tmp, "store/tool/1.0/tool")
            createDirAll(oldTarget.parent!!)
            oldTarget.write("#!/bin/sh\necho 1.0\n")
            val bin = Path(tmp, "bin")
            createDirAll(bin)
            val link = Path(bin, "tool")
            symlink(oldTarget, link)
            val recorded = listOf(LinkRecord(link, oldTarget, LinkKind.Symlink, LinkRole.Binary))
            val upgrade = failingUpgrade(tmp)

            val plan =
                placement(
                    upgrade.payload,
                    upgrade.store,
                    bin,
                    Path(tmp, "Applications"),
                    name = "tool",
                    version = "2.0",
                    replacing = recorded,
                    extras = upgrade.extras,
                )
            assertFailsWith<KetchError> { MacOsPlatform().place(plan) }
            assertEquals(oldTarget, readLink(link))
            assertEquals(listOf("tool"), listDir(bin), "no aside left behind")
        }
    }

    @Test
    fun aFailedPlacementPutsTheOldAppCopyBack() {
        withTempDir { tmp ->
            val oldBundle = Path(tmp, "store/tool/1.0/Tool.app")
            createDirAll(Path(oldBundle, "Contents"))
            Path(oldBundle, "Contents/Info.plist").write("1.0")
            val apps = Path(tmp, "Applications")
            val copy = Path(apps, "Tool.app")
            createDirAll(Path(copy, "Contents"))
            Path(copy, "Contents/Info.plist").write("1.0")
            val recorded = listOf(LinkRecord(copy, oldBundle, LinkKind.CopiedApp, LinkRole.Binary))
            val upgrade = failingUpgrade(tmp)
            val newBundle = Path(upgrade.payload, "Tool.app/Contents")
            createDirAll(newBundle)
            Path(newBundle, "Info.plist").write("2.0")

            val plan =
                placement(
                    upgrade.payload,
                    upgrade.store,
                    Path(tmp, "bin"),
                    apps,
                    name = "tool",
                    version = "2.0",
                    kind = PackageKind.App,
                    replacing = recorded,
                    extras = upgrade.extras,
                )
            assertFailsWith<KetchError> { MacOsPlatform().place(plan) }
            assertEquals("1.0", Path(copy, "Contents/Info.plist").read())
            assertEquals(listOf("Tool.app"), listDir(apps), "no aside left behind")
        }
    }

    @Test
    fun aFailedRelinkKeepsTheLivePrefixOverAStaleOldCopy() {
        withTempDir { tmp ->
            val store = Path(tmp, "store/tool/1.0")
            val tool = program(store, "tool", "#!/bin/sh\necho live\n")
            Path(store, "README").write("live")
            // Left by an earlier swap that was interrupted before its cleanup.
            val stale = Path(tmp, "store/tool/1.0.old")
            createDirAll(stale)
            Path(stale, "tool").write("stale")
            val blocker = Path(tmp, "blocker")
            blocker.write("not a folder")
            val extras = listOf(ExtraPlacement("README", Path(blocker, "README"), LinkRole.Man))

            // Relink and rollback place a payload that is already the store.
            val plan = placement(store, store, Path(tmp, "bin"), Path(tmp, "Applications"), name = "tool", extras = extras)
            assertFailsWith<KetchError> { MacOsPlatform().place(plan) }
            assertEquals("#!/bin/sh\necho live\n", tool.read())
        }
    }

    @Test
    fun aLinkThatNowPointsElsewhereSurvivesUninstall() {
        withTempDir { tmp ->
            val ours = Path(tmp, "ours")
            val theirs = Path(tmp, "theirs")
            ours.write("x")
            theirs.write("x")
            val link = Path(tmp, "tool")
            symlink(theirs, link)

            val record = LinkRecord(link, ours, LinkKind.Symlink, LinkRole.Binary)
            val platform = MacOsPlatform()
            platform.unplace(listOf(record), Report.silent())
            assertNotNull(entryKind(link), "not ours to remove")

            removeFile(link)
            symlink(ours, link)
            platform.unplace(listOf(record), Report.silent())
            assertNull(entryKind(link))
        }
    }
}
