package ketch.core.extra

import fs.atomic
import fs.createDirAll
import fs.joinPath
import fs.readText
import ketch.core.model.ExtraKind
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SelfDocsTest {
    @Test
    fun ketchsOwnDocsAreWrittenWhereTheirExtrasPoint(): Unit =
        withTempDir { prefix ->
            val docs =
                SelfDocs(
                    manPages = { dir ->
                        createDirAll(dir)
                        listOf("ketch.1", "ketch-install.1").map { joinPath(dir, it).also { p -> atomic(p, "page".encodeToByteArray()) } }
                    },
                    completion = { shell -> "complete ${shell.asStr}".encodeToByteArray() },
                )
            val extras = writeKetchDocs(prefix, docs)

            // Every entry must pass the same classification a manifest's would, and name a file that is there.
            val classified = extras.map(::classify)
            assertEquals(listOf(ExtraKind.Man, ExtraKind.Man), classified.take(2).map { it.kind })
            assertEquals("share/man/man1/ketch.1", classified[0].relPath)
            for (c in classified.drop(2)) {
                assertEquals(ExtraKind.Completion, c.kind)
                assertEquals("complete ${c.shell!!.asStr}", readText(resolveUnder(prefix, c.relPath)))
            }
            assertEquals(2 + 5, extras.size)
        }
}
