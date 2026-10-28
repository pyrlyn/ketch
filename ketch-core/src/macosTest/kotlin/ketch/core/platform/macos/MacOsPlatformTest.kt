package ketch.core.platform.macos

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.joinPath
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
            val app = joinPath(root, "Thing.app")
            createDirAll(joinPath(app, "Contents/Updater.app"))
            createDirAll(joinPath(root, "Extra.app"))

            val found = findAppBundles(root).sortedBy { it.toString() }
            assertEquals(listOf(joinPath(root, "Extra.app"), app), found)
        }
    }

    @Test
    fun aFailedUpgradeLeavesTheInstalledVersionInPlace() {
        withTempDir { tmp ->
            val store = joinPath(tmp, "store/tool/1.0")
            createDirAll(store)
            joinPath(store, "tool").write("the working version")

            // Nothing to move: the version already installed must survive.
            assertFailsWith<KetchError> { moveIntoStore(joinPath(tmp, "missing"), store) }
            assertEquals("the working version", joinPath(store, "tool").read())

            val payload = joinPath(tmp, "payload")
            createDirAll(payload)
            joinPath(payload, "tool").write("the new version")
            moveIntoStore(payload, store)
            assertEquals("the new version", joinPath(store, "tool").read())
            assertTrue(exists(joinPath(tmp, "store/tool/1.0.old")), "the previous version stays until links succeed")
            assertNull(entryKind(joinPath(tmp, "store/tool/1.0.incoming")))
            restoreRetiredStore(store)
            assertEquals("the working version", joinPath(store, "tool").read())
            finalizeStoreSwap(store)
            assertNull(entryKind(joinPath(tmp, "store/tool/1.0.old")))
        }
    }

    @Test
    fun unplaceLeavesAFileTheUserPutWhereALinkWas() {
        withTempDir { tmp ->
            val target = joinPath(tmp, "store/pkg/1.0/tool")
            createDirAll(target.parent!!)
            target.write("#!/bin/sh\n")

            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            val link = joinPath(bin, "tool")
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
            val store = joinPath(tmp, "store/thing/1.0")
            val bundle = joinPath(store, "Thing.app")
            createDirAll(joinPath(bundle, "Contents"))
            joinPath(bundle, "Contents/Info.plist").write("x")
            val apps = joinPath(tmp, "Applications")
            createDirAll(apps)
            val appLink = joinPath(apps, "Thing.app")
            copyTree(bundle, appLink)
            val appRecord = LinkRecord(appLink, bundle, LinkKind.CopiedApp, LinkRole.Binary)

            platform.unplace(listOf(appRecord), Report.silent())
            assertNull(entryKind(appLink))

            createDirAll(joinPath(appLink, "Contents"))
            joinPath(appLink, "Contents/mine.txt").write("the user's own copy")
            removeAny(store)
            platform.unplace(listOf(appRecord), Report.silent())
            assertTrue(isFile(joinPath(appLink, "Contents/mine.txt")), "a stale record must not authorize deleting the user's bundle")
        }
    }

    @Test
    fun aBinaryNameAnotherPackageOwnsIsNotTakenOver() {
        withTempDir { tmp ->
            val mine = joinPath(tmp, "store/mine")
            val theirs = joinPath(tmp, "store/theirs/1.0")
            createDirAll(joinPath(mine, "1.0"))
            createDirAll(theirs)
            val target = joinPath(mine, "1.0/tool")
            target.write("#!/bin/sh\n")
            joinPath(theirs, "tool").write("#!/bin/sh\n")

            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            symlink(joinPath(theirs, "tool"), joinPath(bin, "tool"))
            assertFailsWith<KetchError> { linkBinary(target, bin, "tool", mine, emptyList(), Claims()) }
            assertEquals(joinPath(theirs, "tool"), readLink(joinPath(bin, "tool")), "the other package must still own its link")

            // An older version of the same package is ours to replace.
            val old = joinPath(mine, "0.9")
            createDirAll(old)
            joinPath(old, "tool").write("#!/bin/sh\n")
            removeFile(joinPath(bin, "tool"))
            symlink(joinPath(old, "tool"), joinPath(bin, "tool"))
            val record = linkBinary(target, bin, "tool", mine, emptyList(), Claims())
            assertEquals(target, readLink(record.link))
        }
    }

    @Test
    fun discoverPrefersCapitalBinOverNoisySiblings() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            val keep = program(joinPath(payload, "Bin"), "rg", "#!/bin/sh\necho rg\n")
            program(payload, "noise", "#!/bin/sh\necho noise\n")

            val found = discoverExecutables(MacOsPlatform(), payload, "rg")
            assertEquals(listOf(keep), found, "$found")
        }
    }

    @Test
    fun placeLinksClassifiedExtraPathsAndRecordsThem() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            createDirAll(joinPath(payload, "complete"))
            createDirAll(joinPath(payload, "doc"))
            joinPath(payload, "complete/rg.bash").write("# bash\n")
            joinPath(payload, "doc/rg.1").write(".TH RG 1\n")
            program(payload, "rg")

            val store = joinPath(tmp, "store/rg/1.0")
            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            val manDest = joinPath(tmp, "man/man1/rg.1")
            val compDest = joinPath(tmp, "completions/rg")
            val extras =
                listOf(
                    ExtraPlacement("complete/rg.bash", compDest, LinkRole.Completion),
                    ExtraPlacement("doc/rg.1", manDest, LinkRole.Man),
                )
            val plan = placement(payload, store, bin, joinPath(tmp, "Applications"), extras = extras)
            val links = MacOsPlatform().place(plan)
            assertTrue(links.any { it.role == LinkRole.Man && it.link == manDest }, "$links")
            assertTrue(links.any { it.role == LinkRole.Completion && it.link == compDest }, "$links")
            assertEquals(joinPath(store, "doc/rg.1"), readLink(manDest))
            assertEquals(joinPath(store, "complete/rg.bash"), readLink(compDest))

            MacOsPlatform().unplace(links, Report.silent())
            assertNull(entryKind(manDest))
            assertNull(entryKind(compDest))
        }
    }

    @Test
    fun placementChecksAllBinaryDestinationsBeforeReplacingAny() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            for (name in listOf("first", "second")) program(payload, name)

            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            val external = joinPath(tmp, "external")
            external.write("#!/bin/sh\n")
            symlink(external, joinPath(bin, "second"))

            val store = joinPath(tmp, "store/pkg/1.0")
            val plan = placement(payload, store, bin, joinPath(tmp, "Applications"), name = "pkg")

            assertFailsWith<KetchError> { preflightDestinations(MacOsPlatform(), plan, store.parent!!) }
            assertNull(entryKind(joinPath(bin, "first")), "preflight must not create links")
        }
    }

    @Test
    fun placementPreflightChecksThePackageNameForASingleBuildArtifact() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            program(payload, "tool-macos-arm64")

            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            val external = joinPath(tmp, "external")
            external.write("#!/bin/sh\n")
            symlink(external, joinPath(bin, "tool"))

            val store = joinPath(tmp, "store/tool/1.0")
            val plan = placement(payload, store, bin, joinPath(tmp, "Applications"), name = "tool")

            assertFailsWith<KetchError> { preflightDestinations(MacOsPlatform(), plan, store.parent!!) }
        }
    }

    @Test
    fun anAppKetchDidNotInstallIsNeverDeleted() {
        withTempDir { tmp ->
            val packageDir = joinPath(tmp, "store/thing")
            val bundle = joinPath(packageDir, "1.0/Thing.app")
            createDirAll(joinPath(bundle, "Contents"))
            joinPath(bundle, "Contents/Info.plist").write("x")

            val apps = joinPath(tmp, "Applications")
            val existing = joinPath(apps, "Thing.app")
            createDirAll(existing)
            joinPath(existing, "mine.txt").write("the user's own copy")

            assertFailsWith<KetchError> { placeApp(bundle, apps, false, packageDir, emptyList(), Claims()) }
            assertTrue(isFile(joinPath(existing, "mine.txt")), "must not be deleted")

            // A copied bundle leaves no mark on disk; the record we wrote when we placed it is the only thing that
            // makes it ours to replace.
            val recorded = listOf(LinkRecord(existing, bundle, LinkKind.CopiedApp, LinkRole.Binary))
            val claims = Claims()
            placeApp(bundle, apps, false, packageDir, recorded, claims)
            claims.discard()
            assertTrue(isFile(joinPath(existing, "Contents/Info.plist")))
            assertNull(entryKind(joinPath(existing, "mine.txt")))
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
        val payload = joinPath(tmp, "payload")
        program(payload, "tool", "#!/bin/sh\necho 2.0\n")
        joinPath(payload, "README").write("2.0")
        val blocker = joinPath(tmp, "blocker")
        blocker.write("not a folder")
        val extras = listOf(ExtraPlacement("README", joinPath(blocker, "README"), LinkRole.Man))
        return FailingUpgrade(payload, joinPath(tmp, "store/tool/2.0"), extras)
    }

    @Test
    fun aFailedPlacementPutsTheOldLinksBack() {
        withTempDir { tmp ->
            val oldTarget = joinPath(tmp, "store/tool/1.0/tool")
            createDirAll(oldTarget.parent!!)
            oldTarget.write("#!/bin/sh\necho 1.0\n")
            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            val link = joinPath(bin, "tool")
            symlink(oldTarget, link)
            val recorded = listOf(LinkRecord(link, oldTarget, LinkKind.Symlink, LinkRole.Binary))
            val upgrade = failingUpgrade(tmp)

            val plan =
                placement(
                    upgrade.payload,
                    upgrade.store,
                    bin,
                    joinPath(tmp, "Applications"),
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
            val oldBundle = joinPath(tmp, "store/tool/1.0/Tool.app")
            createDirAll(joinPath(oldBundle, "Contents"))
            joinPath(oldBundle, "Contents/Info.plist").write("1.0")
            val apps = joinPath(tmp, "Applications")
            val copy = joinPath(apps, "Tool.app")
            createDirAll(joinPath(copy, "Contents"))
            joinPath(copy, "Contents/Info.plist").write("1.0")
            val recorded = listOf(LinkRecord(copy, oldBundle, LinkKind.CopiedApp, LinkRole.Binary))
            val upgrade = failingUpgrade(tmp)
            val newBundle = joinPath(upgrade.payload, "Tool.app/Contents")
            createDirAll(newBundle)
            joinPath(newBundle, "Info.plist").write("2.0")

            val plan =
                placement(
                    upgrade.payload,
                    upgrade.store,
                    joinPath(tmp, "bin"),
                    apps,
                    name = "tool",
                    version = "2.0",
                    kind = PackageKind.App,
                    replacing = recorded,
                    extras = upgrade.extras,
                )
            assertFailsWith<KetchError> { MacOsPlatform().place(plan) }
            assertEquals("1.0", joinPath(copy, "Contents/Info.plist").read())
            assertEquals(listOf("Tool.app"), listDir(apps), "no aside left behind")
        }
    }

    @Test
    fun aFailedRelinkKeepsTheLivePrefixOverAStaleOldCopy() {
        withTempDir { tmp ->
            val store = joinPath(tmp, "store/tool/1.0")
            val tool = program(store, "tool", "#!/bin/sh\necho live\n")
            joinPath(store, "README").write("live")
            // Left by an earlier swap that was interrupted before its cleanup.
            val stale = joinPath(tmp, "store/tool/1.0.old")
            createDirAll(stale)
            joinPath(stale, "tool").write("stale")
            val blocker = joinPath(tmp, "blocker")
            blocker.write("not a folder")
            val extras = listOf(ExtraPlacement("README", joinPath(blocker, "README"), LinkRole.Man))

            // Relink and rollback place a payload that is already the store.
            val plan = placement(store, store, joinPath(tmp, "bin"), joinPath(tmp, "Applications"), name = "tool", extras = extras)
            assertFailsWith<KetchError> { MacOsPlatform().place(plan) }
            assertEquals("#!/bin/sh\necho live\n", tool.read())
        }
    }

    @Test
    fun aLinkThatNowPointsElsewhereSurvivesUninstall() {
        withTempDir { tmp ->
            val ours = joinPath(tmp, "ours")
            val theirs = joinPath(tmp, "theirs")
            ours.write("x")
            theirs.write("x")
            val link = joinPath(tmp, "tool")
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
