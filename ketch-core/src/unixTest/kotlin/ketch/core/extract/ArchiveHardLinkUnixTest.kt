package ketch.core.extract

import archive.TarWriter
import archive.ZipWriter
import fs.bytesIfExists
import fs.joinPath
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tar hard links and the Finder metadata zip skips. The hard-link guards in [unpackTar] and [finishDeferredLinks]
 * (target path, deferral, the walk that refuses a link below the destination) had no test of their own.
 */
class ArchiveHardLinkUnixTest {
    @Test
    fun aHardLinkListedBeforeItsTargetIsCreatedOnceTheTargetIsOnDisk(): Unit =
        withTempDir { dest ->
            val archive =
                TarWriter()
                    .apply {
                        hardLink("bin/tool-alias", "bin/tool")
                        file("bin/tool", bytes("hi"), octal("755"))
                    }.finish()
            unpackTar(source(archive), dest, unlimited())
            val alias = joinPath(dest, "bin/tool-alias")
            assertFalse(isSymlink(alias), "a hard link must not become a symlink")
            assertContentEquals(bytes("hi"), bytesIfExists(alias))
        }

    @Test
    fun aHardLinkWhoseTargetLeavesThePayloadIsRefused() {
        for (target in listOf("../secret", "bin/../../secret", "/etc/passwd")) {
            withTempDir { outer ->
                write(joinPath(outer, "secret"), bytes("outside"))
                val dest = dir(outer, "dest")
                val archive = TarWriter().apply { hardLink("stolen", target) }.finish()
                val message = refusal { unpackTar(source(archive), dest, unlimited()) }
                assertTrue("escapes" in message, "$target: $message")
                assertFalse(exists(joinPath(dest, "stolen")), "$target: the link was created anyway")
            }
        }
    }

    /** The kernel resolves a hard link's source too: a link below the destination must not pull a file into it. */
    @Test
    fun aHardLinkResolvedThroughASymlinkBelowTheDestinationIsRefused(): Unit =
        withTempDir { outer ->
            write(joinPath(outer, "secret"), bytes("outside"))
            val dest = dir(outer, "dest")
            fs.symlink(Path(".."), joinPath(dest, "up"))
            val archive = TarWriter().apply { hardLink("stolen", "up/secret") }.finish()
            val message = refusal { unpackTar(source(archive), dest, unlimited()) }
            assertTrue("resolves through" in message, message)
            assertFalse(exists(joinPath(dest, "stolen")), "the outside file was linked in")
        }

    @Test
    fun finderMetadataInAZipIsNotPayload(): Unit =
        withTempDir { outer ->
            val src = joinPath(outer, "tool.zip")
            write(
                src,
                ZipWriter()
                    .apply {
                        file("tool/bin/tool", bytes("#!/bin/sh\n"), octal("755"))
                        file("__MACOSX/tool/bin/._tool", bytes("resource fork"))
                        file("tool/.DS_Store", bytes("finder"))
                    }.finish(),
            )
            val dest = dir(outer, "dest")
            unpackZip(src, dest, unlimited())
            assertTrue(exists(joinPath(dest, "tool/bin/tool")))
            assertFalse(exists(joinPath(dest, "__MACOSX")), "__MACOSX was extracted")
            assertFalse(exists(joinPath(dest, "tool/.DS_Store")), ".DS_Store was extracted")
        }
}
