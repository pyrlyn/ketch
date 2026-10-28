package ketch.cmd

import fs.TempDir
import fs.atomic
import fs.readText
import ketch.core.error.KetchError
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuestionnaireTest {
    @Test
    fun nonForceWriteNeverTruncatesAnExistingFile() {
        TempDir.create("ketch-test-").use { tmp ->
            val path = Path(tmp.path, "ketch.toml")
            atomic(path, "original\n".encodeToByteArray())
            assertFailsWith<KetchError> { writeManifest(path, "replacement\n", false) }
            assertEquals("original\n", readText(path))
        }
    }
}
