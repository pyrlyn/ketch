@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.fsio

import fs.EntryKind
import fs.atomic
import fs.entryKind
import fs.listDir
import fs.mode
import fs.readText
import ketch.core.error.KetchError
import ketch.core.testing.withTempDir
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FsioTest {
    @Test
    fun writesIntoAMissingDirectoryAndReplacesWhole() =
        withTempDir { dir ->
            val file = Path(dir, "a/b/state.json")
            writePrivate(file, "one".encodeToByteArray())
            writePrivate(file, "two".encodeToByteArray())
            assertEquals("two", readText(file))
            assertEquals(1, listDir(file.parent!!).size, "no temp file may be left behind")
        }

    @Test
    fun theFileIsPrivateEvenOverAWiderOne() =
        withTempDir { dir ->
            val file = Path(dir, "ketch.lock")
            atomic(file, "old".encodeToByteArray())
            chmod(file.toString(), "644".toInt(8).convert())
            writePrivate(file, "new".encodeToByteArray())
            assertEquals("600".toInt(8), mode(file))
        }

    @Test
    fun aSymlinkIsReplacedNotFollowed() =
        withTempDir { dir ->
            val real = Path(dir, "real")
            val link = Path(dir, "link")
            atomic(real, "keep".encodeToByteArray())
            check(symlink(real.toString(), link.toString()) == 0)
            writePrivate(link, "new".encodeToByteArray())
            assertEquals("keep", readText(real))
            assertEquals(EntryKind.File, entryKind(link))
        }

    @Test
    fun anErrorNamesThePathOnce() =
        withTempDir { dir ->
            val blocker = Path(dir, "file")
            atomic(blocker, "x".encodeToByteArray())
            val err = assertFailsWith<KetchError> { writePrivate(Path(blocker, "child"), "x".encodeToByteArray()) }
            assertEquals(1, err.message.split("child").size - 1, err.message)
        }
}
