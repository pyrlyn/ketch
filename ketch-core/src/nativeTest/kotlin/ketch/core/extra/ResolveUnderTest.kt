package ketch.core.extra

import fs.joinPath
import ketch.core.error.KetchError
import ketch.core.testing.withTempDir
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolveUnderTest {
    @Test
    fun resolveUnderRequiresAFileInsideThePayload() =
        withTempDir { root ->
            SystemFileSystem.createDirectories(joinPath(root, "doc"))
            SystemFileSystem.sink(joinPath(root, "doc/rg.1")).buffered().use { it.writeString(".TH RG 1\n") }

            assertEquals(joinPath(root, "doc/rg.1"), resolveUnder(root, "doc/rg.1"))
            for (bad in listOf("doc/missing.1", "../doc/rg.1", "doc")) {
                assertFailsWith<KetchError>(bad) { resolveUnder(root, bad) }
            }
        }
}
