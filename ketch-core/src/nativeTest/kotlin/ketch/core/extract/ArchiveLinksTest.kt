package ketch.core.extract

import fs.bytesIfExists
import fs.createDirAll
import fs.isDir
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
    fun refusesLinksThatEscapeThePayload() {
        checkLinkTarget("bin/tool", "../lib/x.dylib")
        assertFails { checkLinkTarget("bin/tool", "../../../.ssh/id") }
        assertFails { checkLinkTarget("tool", "/etc/passwd") }
        assertFails { checkLinkTarget("tool", "../outside") }
    }
}
