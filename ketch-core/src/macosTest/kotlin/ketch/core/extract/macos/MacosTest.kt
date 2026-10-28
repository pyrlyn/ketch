package ketch.core.extract.macos

import fs.EntryKind
import fs.atomic
import fs.createDirAll
import fs.entryKind
import fs.joinPath
import ketch.core.error.KetchError
import ketch.core.platform.symlink
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosTest {
    /** A stand-in for a mounted volume: `copyVolume` only reads a directory. */
    private fun <T> withVolumeLink(
        target: String,
        body: (mount: Path, dest: Path) -> T,
    ): T =
        withTempDir { root ->
            val mount = joinPath(root, "volume")
            val resources = joinPath(mount, "App.app/Contents/Resources")
            createDirAll(resources)
            atomic(joinPath(resources, "real"), "x".encodeToByteArray())
            symlink(Path(target), joinPath(resources, "link"))
            val dest = joinPath(root, "dest")
            createDirAll(dest)
            body(mount, dest)
        }

    @Test
    fun aNestedLinkOutOfAVolumeIsRefused() {
        for (target in listOf("/etc/hosts", "../../../../..", "../../../../../outside")) {
            withVolumeLink(target) { mount, dest ->
                val err = assertFailsWith<KetchError> { copyVolume(mount, dest) }
                assertTrue(err.message.orEmpty().contains("resolves outside"), "$target: ${err.message}")
            }
        }
    }

    @Test
    fun aNestedLinkInsideAVolumeIsKept() {
        withVolumeLink("real") { mount, dest ->
            copyVolume(mount, dest)
            assertEquals(EntryKind.Symlink, entryKind(joinPath(dest, "App.app/Contents/Resources/link")))
        }
    }

    @Test
    fun readsTheMountPointOutOfHdiutilOutput() {
        withTempDir { root ->
            val mount = joinPath(root, "dmg.T0hgqZ")
            createDirAll(mount)

            val listing =
                "/dev/disk4          \tGUID_partition_scheme\t\n" +
                    "/dev/disk4s1        \tApple_HFS            \t$mount\n"
            assertEquals(mount, findMountPoint(listing))
        }
    }

    @Test
    fun prefersTheLastMountWhenHdiutilListsMultipleVolumes() {
        withTempDir { root ->
            val helper = joinPath(root, "helper.T0hgqZ")
            val app = joinPath(root, "MyApp.T0hgqZ")
            createDirAll(helper)
            createDirAll(app)

            val listing =
                "/dev/disk4          \tGUID_partition_scheme\t\n" +
                    "/dev/disk4s1        \tEFI                  \t$helper\n" +
                    "/dev/disk4s2        \tApple_HFS            \t$app\n"
            assertEquals(app, findMountPoint(listing))
        }
    }

    @Test
    fun ignoresPartitionsWithNoMountPoint() {
        assertNull(findMountPoint("/dev/disk4\tGUID_partition_scheme\t\n"))
    }

    @Test
    fun anImageThatMountsNothingCanStillBeDetached() {
        // Nothing mounted, so the device node is the only handle left for detaching an image that is nonetheless
        // attached.
        val listing = "/dev/disk4\tGUID_partition_scheme\t\n/dev/disk4s1\tApple_HFS\t\n"
        assertNull(findMountPoint(listing))
        assertEquals("/dev/disk4", findDevice(listing))
    }

    @Test
    fun aPlainFileNamedDmgIsNotClaimedWithoutKolyTrailer() {
        withTempDir { root ->
            val path = joinPath(root, "release.dmg")
            val bytes = "not a disk image".encodeToByteArray()
            atomic(path, bytes)

            assertFalse(DmgExtractor.detect(path, bytes))
        }
    }

    @Test
    fun aPlainFileNamedPkgIsNotClaimedWithoutXarMagic() {
        withTempDir { root ->
            val path = joinPath(root, "installer.pkg")
            val bytes = "not a flat package".encodeToByteArray()
            atomic(path, bytes)

            assertFalse(PkgExtractor.detect(path, bytes))
        }
    }

    @Test
    fun kolyTrailerIsClaimedWithoutADmgExtension() {
        withTempDir { root ->
            val path = joinPath(root, "disk-image")
            val bytes = ByteArray(512)
            "koly".encodeToByteArray().copyInto(bytes)
            atomic(path, bytes)

            assertTrue(DmgExtractor.detect(path, bytes.copyOf(4)))
        }
    }

    @Test
    fun xarMagicIsClaimedWithoutAPkgExtension() {
        withTempDir { root ->
            val path = joinPath(root, "flat-package")
            val head = "xar!".encodeToByteArray() + byteArrayOf(0x1c)
            atomic(path, head)

            assertTrue(PkgExtractor.detect(path, head))
        }
    }
}
