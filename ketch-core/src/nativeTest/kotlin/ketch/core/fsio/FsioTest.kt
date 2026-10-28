package ketch.core.fsio

import fs.atomic
import fs.joinPath
import fs.listDir
import fs.readText
import ketch.core.error.KetchError
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FsioTest {
    @Test
    fun writesIntoAMissingDirectoryAndReplacesWhole() =
        withTempDir { dir ->
            val file = joinPath(dir, "a/b/state.json")
            writePrivate(file, "one".encodeToByteArray())
            writePrivate(file, "two".encodeToByteArray())
            assertEquals("two", readText(file))
            assertEquals(1, listDir(file.parent!!).size, "no temp file may be left behind")
        }

    @Test
    fun anErrorNamesThePathOnce() =
        withTempDir { dir ->
            val blocker = joinPath(dir, "file")
            atomic(blocker, "x".encodeToByteArray())
            val err = assertFailsWith<KetchError> { writePrivate(joinPath(blocker, "child"), "x".encodeToByteArray()) }
            assertEquals(1, err.message.split("child").size - 1, err.message)
        }
}
