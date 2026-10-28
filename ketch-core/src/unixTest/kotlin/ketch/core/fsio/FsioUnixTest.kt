@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.fsio

import fs.EntryKind
import fs.atomic
import fs.entryKind
import fs.joinPath
import fs.mode
import fs.readText
import ketch.core.testing.withTempDir
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals

class FsioUnixTest {
    @Test
    fun theFileIsPrivateEvenOverAWiderOne() =
        withTempDir { dir ->
            val file = joinPath(dir, "ketch.lock")
            atomic(file, "old".encodeToByteArray())
            chmod(file.toString(), "644".toInt(8).convert())
            writePrivate(file, "new".encodeToByteArray())
            assertEquals("600".toInt(8), mode(file))
        }

    @Test
    fun aSymlinkIsReplacedNotFollowed() =
        withTempDir { dir ->
            val real = joinPath(dir, "real")
            val link = joinPath(dir, "link")
            atomic(real, "keep".encodeToByteArray())
            check(symlink(real.toString(), link.toString()) == 0)
            writePrivate(link, "new".encodeToByteArray())
            assertEquals("keep", readText(real))
            assertEquals(EntryKind.File, entryKind(link))
        }
}
