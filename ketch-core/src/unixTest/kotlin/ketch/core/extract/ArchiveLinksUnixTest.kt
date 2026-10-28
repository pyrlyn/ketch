package ketch.core.extract

import archive.TarWriter
import fs.bytesIfExists
import fs.createDirAll
import fs.joinPath
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveLinksUnixTest {
    @Test
    fun aPlantedSymlinkIsNeverWrittenThrough(): Unit =
        withTempDir { outer ->
            val dest = dir(outer, "dest")
            assertFails("an entry named through a planted symlink must be refused") {
                unpackTar(source(plantedSymlinkTar()), dest, unlimited())
            }
            // Member 2 would have been created here had the link been followed.
            assertFalse(exists(joinPath(dest, "e")), "the symlink was resolved anyway")
        }

    /** Each target passes the lexical check alone; through `L -> .` they all resolve above the payload. */
    @Test
    fun aLinkRoutedThroughAnEarlierLinkIsRefused() {
        for ((name, target) in listOf("N" to "L/..", "N" to "L/L/../..", "sub/N" to "../L/L/../..")) {
            withTempDir { outer ->
                val archive =
                    TarWriter()
                        .apply {
                            symlink("L", ".")
                            symlink(name, target)
                        }.finish()
                val dest = dir(outer, "dest")
                val message = refusal { unpackTar(source(archive), dest, unlimited()) }
                assertTrue("resolves outside" in message, "$target: $message")
            }
        }
    }

    @Test
    fun resolvesInsideFollowsEveryLinkAndJudgesDanglingOnesByTheirTarget(): Unit =
        withTempDir { outer ->
            val root = joinPath(outer, "root")
            createDirAll(joinPath(root, "bin"))
            write(joinPath(root, "bin/tool"), bytes("x"))

            fun link(
                name: String,
                target: String,
            ) = joinPath(root, name).also { fs.symlink(Path(target), it) }
            assertTrue(resolvesInside(link("alias", "bin/tool"), root))
            assertTrue(resolvesInside(link("dangling", "bin/missing"), root))
            link("self", ".")
            assertTrue(resolvesInside(joinPath(root, "self/self/bin/tool"), root))
            assertFalse(resolvesInside(link("up", "self/.."), root))
            assertFalse(resolvesInside(link("gone", "self/../nothing"), root))
            assertFalse(resolvesInside(link("abs", "/etc/passwd"), root))
            link("loop-a", "loop-b")
            assertFalse(resolvesInside(link("loop-b", "loop-a"), root))
        }

    @Test
    fun ordinarySymlinksInsideThePayloadStillWork(): Unit =
        withTempDir { dest ->
            val archive =
                TarWriter()
                    .apply {
                        file("bin/tool", bytes("hi"), octal("755"))
                        symlink("bin/tool-alias", "tool")
                    }.finish()
            unpackTar(source(archive), dest, unlimited())
            val alias = joinPath(dest, "bin/tool-alias")
            assertTrue(isSymlink(alias))
            assertContentEquals(bytes("hi"), bytesIfExists(alias))
        }

    /**
     * Archives often list `bin/tool -> tool` before the real file. Deferring the link until unpack finishes is what
     * makes copy materialisation (and late hard links) succeed.
     */
    @Test
    fun aSymlinkListedBeforeItsTargetStillMaterialises(): Unit =
        withTempDir { dest ->
            val archive =
                TarWriter()
                    .apply {
                        symlink("bin/tool-alias", "tool")
                        file("bin/tool", bytes("hi"), octal("755"))
                    }.finish()
            unpackTar(source(archive), dest, unlimited())
            val alias = joinPath(dest, "bin/tool-alias")
            assertTrue(isSymlink(alias))
            assertContentEquals(bytes("hi"), bytesIfExists(alias))
        }
}
