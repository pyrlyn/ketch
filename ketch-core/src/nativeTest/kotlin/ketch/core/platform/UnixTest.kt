package ketch.core.platform

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.mode
import fs.removeAny
import fs.setMode
import fs.walk
import ketch.core.error.KetchError
import ketch.core.model.BinSpec
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnixTest {
    private fun octal(text: String) = text.toInt(8)

    private fun permissions(path: Path) = mode(path)!! and octal("7777")

    @Test
    fun aBinGlobPrefersTheLinkNamedBinaryOverAHelperBesideIt() {
        withTempDir { tmp ->
            Path(tmp, "rtok-hook").write("hook")
            Path(tmp, "rtok").write("main")
            val got = resolveBinSpecs(tmp, listOf(BinSpec(path = "rtok*", name = "rtok")))
            assertEquals(1, got.size)
            assertEquals("rtok", got[0].first.name)
            assertEquals("rtok", got[0].second)
        }
    }

    @Test
    fun aPayloadLinkRoutedThroughAnotherLinkOutOfTheTreeIsNotAProgram() {
        withTempDir { outer ->
            val root = Path(outer, "payload")
            createDirAll(root)
            Path(outer, "outside").write("#!/bin/sh\n")
            Path(root, "inside").write("#!/bin/sh\n")
            symlink(Path("."), Path(root, "L"))
            // Planted by a payload no extractor checked (a DMG or PKG copy): one hop reads `abs`, which is inside, and
            // stops there.
            symlink(Path(outer, "outside"), Path(root, "abs"))
            symlink(Path("abs"), Path(root, "escape"))
            symlink(Path("L/inside"), Path(root, "alias"))

            fun entryFor(name: String) = walk(root, onError = {}).first { it.path.name == name }
            assertNull(payloadExecutableEntry(entryFor("escape"), root))
            assertEquals(Path(root, "alias"), payloadExecutableEntry(entryFor("alias"), root))
        }
    }

    @Test
    fun parentDirIsBinIgnoresAsciiCase() {
        assertTrue(parentDirIsBin(Path("/store/pkg/bin/tool")))
        assertTrue(parentDirIsBin(Path("/store/pkg/Bin/tool")))
        assertTrue(parentDirIsBin(Path("/store/pkg/BIN/tool")))
        assertFalse(parentDirIsBin(Path("/store/pkg/lib/tool")))
        assertFalse(parentDirIsBin(Path("/store/pkg/tool")))
    }

    @Test
    fun makingABinaryExecutableLeavesEveryOtherBitAlone() {
        withTempDir { tmp ->
            val path = Path(tmp, "tool")
            path.write("#!/bin/sh\n")
            setMode(path, octal("600"))

            ensureExecutable(path)

            // Owner read and execute, and nothing for anyone else.
            assertEquals(octal("700"), permissions(path))
        }
    }

    @Test
    fun makingABinaryExecutableAddsTheReadAScriptNeeds() {
        withTempDir { tmp ->
            val path = Path(tmp, "tool")
            path.write("#!/bin/sh\n")
            // What a tar member with a write-only header mode unpacks as.
            setMode(path, octal("200"))

            ensureExecutable(path)

            assertEquals(octal("700"), permissions(path), "an execute-only script cannot run")
        }
    }

    @Test
    fun emptyDestinationIsAvailable() {
        withTempDir { tmp ->
            val owned = Path(tmp, "store/pkg")
            createDirAll(owned)
            destinationAvailable(Path(tmp, "bin/tool"), owned, emptyList())
        }
    }

    /** `bin/tool` in [tmp], linked to [target]. */
    private fun linkTo(
        tmp: Path,
        target: Path,
    ): Path {
        val link = Path(tmp, "bin/tool")
        createDirAll(link.parent!!)
        symlink(target, link)
        return link
    }

    /** `store/pkg` with a program in its `1.0`. */
    private fun ownedTool(tmp: Path): Pair<Path, Path> {
        val owned = Path(tmp, "store/pkg")
        createDirAll(Path(owned, "1.0"))
        val target = Path(owned, "1.0/tool")
        target.write("#!/bin/sh\n")
        return owned to target
    }

    @Test
    fun aSymlinkIntoOwnedIsOurs() {
        withTempDir { tmp ->
            val (owned, target) = ownedTool(tmp)
            val link = linkTo(tmp, target)
            assertTrue(isOurs(link, owned, emptyList()))
            destinationAvailable(link, owned, emptyList())
        }
    }

    @Test
    fun occupiedDestinationThatIsNotOursReturnsAnError() {
        withTempDir { tmp ->
            val owned = Path(tmp, "store/pkg")
            createDirAll(owned)
            val elsewhere = Path(tmp, "elsewhere")
            elsewhere.write("#!/bin/sh\n")
            val link = linkTo(tmp, elsewhere)
            assertFalse(isOurs(link, owned, emptyList()))
            assertFailsWith<KetchError> { destinationAvailable(link, owned, emptyList()) }
        }
    }

    @Test
    fun aSymlinkTargetWithDotdotIsNotTreatedAsOurs() {
        withTempDir { tmp ->
            val owned = Path(tmp, "store/pkg")
            createDirAll(owned)
            Path(tmp, "elsewhere").write("x")
            // Lexically under `owned`, but resolves outside via `..`.
            val sneaky = Path(owned, "1.0", "..", "..", "..", "elsewhere")
            assertTrue(sneaky.toString().startsWith("$owned/"), "precondition: lexical starts_with alone would allow this")
            val link = linkTo(tmp, sneaky)
            assertFalse(isOurs(link, owned, emptyList()), "`..` in a symlink target must not count as owned")
            assertFailsWith<KetchError> { destinationAvailable(link, owned, emptyList()) }
        }
    }

    /** Occupant at the destination path for table-driven ownership cases. */
    private enum class Occupant {
        /** Plain file with no symlink mark — like a copied binary. */
        RegularFile,

        /** Directory tree — like a copied `.app` bundle. */
        Directory,
    }

    private fun prepareDestination(
        tmp: Path,
        occupant: Occupant,
    ): Pair<Path, Path> {
        val owned = Path(tmp, "store/pkg")
        createDirAll(owned)
        val link = Path(tmp, "dest")
        when (occupant) {
            Occupant.RegularFile -> {
                link.write("x")
            }

            Occupant.Directory -> {
                createDirAll(Path(link, "Contents"))
                Path(link, "Contents/Info.plist").write("x")
            }
        }
        return owned to link
    }

    @Test
    fun aRecordedLinkIsOursOnlyWhileTheDiskStillAgrees() {
        // A symlink record whose link still points at the recorded target.
        withTempDir { tmp ->
            val (owned, target) = ownedTool(tmp)
            val link = linkTo(tmp, target)
            val recorded = listOf(LinkRecord(link, target, LinkKind.Symlink, LinkRole.Binary))
            assertTrue(isOurs(link, owned, recorded))
            destinationAvailable(link, owned, recorded)
        }

        // A copied bundle leaves no symlink behind, so the record is the only evidence there can be — and only while
        // the store copy is still there.
        withTempDir { tmp ->
            val (owned, link) = prepareDestination(tmp, Occupant.Directory)
            val target = Path(owned, "1.0/App.app")
            createDirAll(target)
            val recorded = listOf(LinkRecord(link, target, LinkKind.CopiedApp, LinkRole.Binary))
            assertTrue(isOurs(link, owned, recorded))
            destinationAvailable(link, owned, recorded)

            removeAny(target)
            assertFalse(isOurs(link, owned, recorded), "a CopiedApp record must not outlive the store copy it names")
        }
    }

    @Test
    fun aRecordedLinkWhosePlaceNowHoldsAFileIsNotOurs() {
        // The user replaced ketch's link with a copy of their own. A stale record must not authorize deleting it.
        withTempDir { tmp ->
            val (owned, link) = prepareDestination(tmp, Occupant.RegularFile)
            val recorded = listOf(LinkRecord(link, Path(owned, "1.0/tool"), LinkKind.Symlink, LinkRole.Binary))

            assertFalse(isOurs(link, owned, recorded))
            assertFailsWith<KetchError> { destinationAvailable(link, owned, recorded) }
            assertFailsWith<KetchError> { clearDestination(link, owned, recorded, Claims()) }
            assertEquals(EntryKind.File, entryKind(link), "the user's file must survive")
        }
    }

    @Test
    fun aRecordedSymlinkThatPointsSomewhereElseIsNotOurs() {
        withTempDir { tmp ->
            val owned = Path(tmp, "store/pkg")
            createDirAll(Path(owned, "1.0"))
            val elsewhere = Path(tmp, "elsewhere")
            elsewhere.write("x")
            val link = linkTo(tmp, elsewhere)
            val recorded = listOf(LinkRecord(link, Path(owned, "1.0/tool"), LinkKind.Symlink, LinkRole.Binary))

            assertFalse(isOurs(link, owned, recorded))
            assertFailsWith<KetchError> { clearDestination(link, owned, recorded, Claims()) }
        }
    }

    @Test
    fun aRegularFileWithoutAMatchingRecordIsNotOurs() {
        withTempDir { tmp ->
            val (owned, link) = prepareDestination(tmp, Occupant.RegularFile)
            assertFalse(isOurs(link, owned, emptyList()))
            assertFailsWith<KetchError> { destinationAvailable(link, owned, emptyList()) }
        }
    }

    @Test
    fun aDirectoryWithoutAMatchingRecordIsNotOurs() {
        withTempDir { tmp ->
            val (owned, link) = prepareDestination(tmp, Occupant.Directory)
            assertFalse(isOurs(link, owned, emptyList()))
            assertFailsWith<KetchError> { destinationAvailable(link, owned, emptyList()) }
        }
    }

    @Test
    fun clearDestinationRemovesAnOwnedSymlink() {
        withTempDir { tmp ->
            val (owned, target) = ownedTool(tmp)
            val link = linkTo(tmp, target)

            clearDestination(link, owned, emptyList(), Claims())
            assertNull(entryKind(link), "owned symlink should be gone")
        }
    }

    @Test
    fun clearDestinationRefusesANotOursOccupantAndLeavesItInPlace() {
        withTempDir { tmp ->
            val (owned, link) = prepareDestination(tmp, Occupant.RegularFile)

            assertFailsWith<KetchError> { clearDestination(link, owned, emptyList(), Claims()) }
            assertEquals(EntryKind.File, entryKind(link), "must not be deleted")
        }
    }

    // Rust runs this one on Linux only, where this is the move the backend uses; it needs nothing of Linux.
    @Test
    fun aFailedPlaceAfterTheSwapRestoresThePreviousStore() {
        withTempDir { tmp ->
            val store = Path(tmp, "store/tool/1.0")
            createDirAll(store)
            Path(store, "tool").write("working")
            val payload = Path(tmp, "payload")
            createDirAll(payload)
            Path(payload, "tool").write("new")
            moveIntoStore(payload, store)
            assertEquals("new", Path(store, "tool").read())
            assertEquals(EntryKind.Dir, entryKind(sibling(store, ".old")))
            restoreRetiredStore(store)
            assertEquals("working", Path(store, "tool").read())
            finalizeStoreSwap(store)
            assertNull(entryKind(sibling(store, ".old")))
        }
    }
}
