package ketch.core.fsio

import fs.atomic
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
            val file = Path(dir, "a/b/state.json")
            writePrivate(file, "one".encodeToByteArray())
            writePrivate(file, "two".encodeToByteArray())
            assertEquals("two", readText(file))
            assertEquals(1, listDir(file.parent!!).size, "no temp file may be left behind")
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
