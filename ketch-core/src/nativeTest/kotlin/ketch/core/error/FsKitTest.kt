package ketch.core.error

import fs.FsException
import fs.textOrEmpty
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FsKitTest {
    @Test
    fun anFsKitErrorNamesThePathThatFailedOnce() =
        withTempDir { dir ->
            // Reading a directory as text fails on every platform.
            val err = assertFailsWith<FsException> { textOrEmpty(dir) }
            val text = KetchError.fsKit(Path("caller-path"), err).message
            assertEquals(1, text.split(dir.toString()).size - 1, text)
            assertFalse("caller-path" in text, text)
        }
}
