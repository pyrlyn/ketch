package ketch.core.extract

import fs.bytesIfExists
import fs.createDirAll
import fs.isDir
import fs.joinPath
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveLinksTest {
    @Test
    fun materializeSymlinkAsCopyWritesTheTargetFile(): Unit =
        withTempDir { dest ->
            write(joinPath(dest, "tool.exe"), bytes("MZ"))
            val link = joinPath(dest, "tool-alias")
            materializeSymlinkAsCopy("tool.exe", link)
            assertContentEquals(bytes("MZ"), bytesIfExists(link))
            assertFalse(isSymlink(link))
        }

    @Test
    fun materializeSymlinkAsCopyErrorsWhenTargetIsMissing(): Unit =
        withTempDir { dest ->
            val link = joinPath(dest, "tool-alias")
            val message = refusal { materializeSymlinkAsCopy("missing", link) }
            assertTrue("cannot materialise" in message, message)
            assertFalse(exists(link))
        }

    /** A directory link to its own ancestor would be copied into itself while the copy walks it: endless recursion, or a full disk. */
    @Test
    fun materializeSymlinkAsCopyRefusesALinkToItsOwnAncestor(): Unit =
        withTempDir { dest ->
            write(joinPath(dir(dest, "sub"), "file"), bytes("x"))
            for ((name, target) in listOf("d" to ".", "sub/d" to "..", "sub/d" to ".", "sub/d" to "../sub")) {
                val link = joinPath(dest, name)
                val message = refusal { materializeSymlinkAsCopy(target, link) }
                assertTrue("ancestor" in message, "$target: $message")
                assertFalse(exists(link), "$target: nothing written")
            }
        }

    @Test
    fun materializeSymlinkAsCopyWritesADirectoryTree(): Unit =
        withTempDir { dest ->
            val docs = joinPath(dest, "docs")
            createDirAll(joinPath(docs, "nested"))
            write(joinPath(docs, "readme.txt"), bytes("hi"))
            write(joinPath(docs, "nested/more.txt"), bytes("there"))
            val link = joinPath(dest, "share-doc")
            materializeSymlinkAsCopy("docs", link)
            assertTrue(isDir(link))
            assertFalse(isSymlink(link))
            assertContentEquals(bytes("hi"), bytesIfExists(joinPath(link, "readme.txt")))
            assertContentEquals(bytes("there"), bytesIfExists(joinPath(link, "nested/more.txt")))
        }

    @Test
    fun finishDeferredLinksMaterialisesAfterTargetExists(): Unit =
        withTempDir { dest ->
            val link = joinPath(dest, "tool-alias")
            write(joinPath(dest, "tool.exe"), bytes("MZ"))
            finishDeferredLinks(dest, emptyList(), listOf(link to "tool.exe"))
            assertContentEquals(bytes("MZ"), bytesIfExists(link))
        }

    @Test
    fun writeHardlinkFallsBackToCopyWhenLinkFails(): Unit =
        withTempDir { dest ->
            val source = joinPath(dest, "tool.exe")
            write(source, bytes("MZ"))
            // An existing destination makes the hard link fail on every host; the copy must still replace the stale
            // bytes (Windows extracts that refuse hard links).
            val out = joinPath(dest, "tool-alias")
            write(out, bytes("stale"))
            writeHardlink(source, out)
            assertContentEquals(bytes("MZ"), bytesIfExists(out))
        }

    @Test
    fun refusesLinksThatEscapeThePayload() {
        checkLinkTarget("bin/tool", "../lib/x.dylib")
        assertFails { checkLinkTarget("bin/tool", "../../../.ssh/id") }
        assertFails { checkLinkTarget("tool", "/etc/passwd") }
        assertFails { checkLinkTarget("tool", "../outside") }
    }
}
