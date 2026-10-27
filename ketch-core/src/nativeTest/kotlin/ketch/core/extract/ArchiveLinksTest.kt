package ketch.core.extract

import archive.ArchiveException
import archive.TarWriter
import archive.ZipWriter
import archive.bzip2
import archive.gzip
import archive.gzipSource
import fs.EntryKind
import fs.atomic
import fs.bytesIfExists
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.mode
import fs.removeAny
import ketch.core.error.KetchError
import ketch.core.testing.withTempDir
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArchiveLinksTest {
    @Test
    fun aPlantedSymlinkIsNeverWrittenThrough(): Unit =
        withTempDir { outer ->
            val dest = dir(outer, "dest")
            assertFails("an entry named through a planted symlink must be refused") {
                unpackTar(source(plantedSymlinkTar()), dest, unlimited())
            }
            // Member 2 would have been created here had the link been followed.
            assertFalse(exists(Path(dest, "e")), "the symlink was resolved anyway")
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
            val root = Path(outer, "root")
            createDirAll(Path(root, "bin"))
            write(Path(root, "bin/tool"), bytes("x"))

            fun link(
                name: String,
                target: String,
            ) = Path(root, name).also { fs.symlink(Path(target), it) }
            assertTrue(resolvesInside(link("alias", "bin/tool"), root))
            assertTrue(resolvesInside(link("dangling", "bin/missing"), root))
            link("self", ".")
            assertTrue(resolvesInside(Path(root, "self/self/bin/tool"), root))
            assertFalse(resolvesInside(link("up", "self/.."), root))
            assertFalse(resolvesInside(link("gone", "self/../nothing"), root))
            assertFalse(resolvesInside(link("abs", "/etc/passwd"), root))
            link("loop-a", "loop-b")
            assertFalse(resolvesInside(link("loop-b", "loop-a"), root))
        }

    @Test
    fun materializeSymlinkAsCopyWritesTheTargetFile(): Unit =
        withTempDir { dest ->
            write(Path(dest, "tool.exe"), bytes("MZ"))
            val link = Path(dest, "tool-alias")
            materializeSymlinkAsCopy("tool.exe", link)
            assertContentEquals(bytes("MZ"), bytesIfExists(link))
            assertFalse(isSymlink(link))
        }

    @Test
    fun materializeSymlinkAsCopyErrorsWhenTargetIsMissing(): Unit =
        withTempDir { dest ->
            val link = Path(dest, "tool-alias")
            val message = refusal { materializeSymlinkAsCopy("missing", link) }
            assertTrue("cannot materialise" in message, message)
            assertFalse(exists(link))
        }

    /** A directory link to its own ancestor would be copied into itself while the copy walks it: endless recursion, or a full disk. */
    @Test
    fun materializeSymlinkAsCopyRefusesALinkToItsOwnAncestor(): Unit =
        withTempDir { dest ->
            write(Path(dir(dest, "sub"), "file"), bytes("x"))
            for ((name, target) in listOf("d" to ".", "sub/d" to "..", "sub/d" to ".", "sub/d" to "../sub")) {
                val link = Path(dest, name)
                val message = refusal { materializeSymlinkAsCopy(target, link) }
                assertTrue("ancestor" in message, "$target: $message")
                assertFalse(exists(link), "$target: nothing written")
            }
        }

    @Test
    fun materializeSymlinkAsCopyWritesADirectoryTree(): Unit =
        withTempDir { dest ->
            val docs = Path(dest, "docs")
            createDirAll(Path(docs, "nested"))
            write(Path(docs, "readme.txt"), bytes("hi"))
            write(Path(docs, "nested/more.txt"), bytes("there"))
            val link = Path(dest, "share-doc")
            materializeSymlinkAsCopy("docs", link)
            assertTrue(isDir(link))
            assertFalse(isSymlink(link))
            assertContentEquals(bytes("hi"), bytesIfExists(Path(link, "readme.txt")))
            assertContentEquals(bytes("there"), bytesIfExists(Path(link, "nested/more.txt")))
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
            val alias = Path(dest, "bin/tool-alias")
            assertTrue(isSymlink(alias))
            assertContentEquals(bytes("hi"), bytesIfExists(alias))
        }

    @Test
    fun finishDeferredLinksMaterialisesAfterTargetExists(): Unit =
        withTempDir { dest ->
            val link = Path(dest, "tool-alias")
            write(Path(dest, "tool.exe"), bytes("MZ"))
            finishDeferredLinks(dest, emptyList(), listOf(link to "tool.exe"))
            assertContentEquals(bytes("MZ"), bytesIfExists(link))
        }

    @Test
    fun writeHardlinkFallsBackToCopyWhenLinkFails(): Unit =
        withTempDir { dest ->
            val source = Path(dest, "tool.exe")
            write(source, bytes("MZ"))
            // An existing destination makes the hard link fail on every host; the copy must still replace the stale
            // bytes (Windows extracts that refuse hard links).
            val out = Path(dest, "tool-alias")
            write(out, bytes("stale"))
            writeHardlink(source, out)
            assertContentEquals(bytes("MZ"), bytesIfExists(out))
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
            val alias = Path(dest, "bin/tool-alias")
            assertTrue(isSymlink(alias))
            assertContentEquals(bytes("hi"), bytesIfExists(alias))
        }

    @Test
    fun refusesLinksThatEscapeThePayload() {
        checkLinkTarget("bin/tool", "../lib/x.dylib")
        assertFails { checkLinkTarget("bin/tool", "../../../.ssh/id") }
        assertFails { checkLinkTarget("tool", "/etc/passwd") }
        assertFails { checkLinkTarget("tool", "../outside") }
    }
}
