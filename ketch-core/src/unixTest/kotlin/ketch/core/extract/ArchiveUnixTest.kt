package ketch.core.extract

import archive.TarWriter
import archive.ZipWriter
import archive.bzip2
import archive.gzip
import fs.EntryKind
import fs.bytesIfExists
import fs.entryKind
import fs.isDir
import fs.joinPath
import fs.mode
import fs.removeAny
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArchiveUnixTest {
    @Test
    fun extractsATarGzAndKeepsTheExecutableBit(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "t.tar.gz")
            write(src, tarGzWith(Triple("rg-1/rg", bytes("#!/bin/sh\n"), octal("755"))))
            val dest = dir(tmp, "out")
            TarGzExtractor.extract(src, dest)
            val binary = joinPath(dest, "rg-1/rg")
            assertEquals(EntryKind.File, entryKind(binary))
            assertNotEquals(0, modeOf(binary) and octal("111"))
        }

    @Test
    fun bzip2ArchivesUseTheSameTraversalGuardAsTar(): Unit =
        withTempDir { outer ->
            val src = joinPath(outer, "payload.tar.bz2")
            write(src, bzip2(plantedSymlinkTar()))
            val dest = dir(outer, "dest")
            assertFails { TarBz2Extractor.extract(src, dest) }
            assertFalse(exists(joinPath(outer, "e")))
        }

    /** A zip the way Windows tools write one: every member 0644, with no execute bit anywhere. Gradle and Maven `-bin.zip` distributions are these. */
    @Test
    fun aProgramInAZipWithoutAnExecuteBitStillComesOutRunnable(): Unit =
        withTempDir { tmp ->
            val archive = joinPath(tmp, "payload.zip")
            val zip =
                ZipWriter().apply {
                    file("bin/tool", bytes("#!/bin/sh\necho hi\n"), octal("644"))
                    file("README.md", bytes("# hello\n"), octal("644"))
                }
            write(archive, zip.finish())
            val dest = dir(tmp, "out")
            ZipExtractor.extract(archive, dest)
            assertEquals(octal("744"), modeOf(joinPath(dest, "bin/tool")), "placement only links a file it can run")
            // A file that is not a program keeps the mode the archive gave it.
            assertEquals(octal("644"), modeOf(joinPath(dest, "README.md")))
        }

    @Test
    fun tarDirectoryMembersKeepMode(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "t.tar.gz")
            write(src, gzip(TarWriter().apply { dir("private/", octal("700")) }.finish()))
            val dest = dir(tmp, "out")
            TarGzExtractor.extract(src, dest)
            val private = joinPath(dest, "private")
            assertTrue(isDir(private))
            assertEquals(octal("700"), modeOf(private))
        }

    /** Nix and some reproducible tarballs mark directories 0555. Applied as written, the mode either stops the children from being written or leaves a tree nothing can remove. */
    @Test
    fun aReadOnlyDirectoryExtractsAndStaysRemovableInEitherOrder() {
        for (dirFirst in listOf(true, false)) {
            withTempDir { outer ->
                val dest = dir(outer, "dest")
                unpackTar(source(tarWithReadOnlyDir(dirFirst)), dest, unlimited())
                assertContentEquals(bytes("hi"), bytesIfExists(joinPath(dest, "ro/file")), "dirFirst=$dirFirst")
                assertEquals(octal("755"), modeOf(joinPath(dest, "ro")) and octal("777"), "dirFirst=$dirFirst: owner always gets rwx")
                removeAny(dest)
                assertFalse(exists(dest), "dirFirst=$dirFirst")
            }
        }
    }

    @Test
    fun setuidBitsFromAnArchiveAreDropped(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "t.tar.gz")
            write(src, tarGzWith(Triple("tool", bytes("#!/bin/sh\n"), octal("4755"))))
            val dest = dir(tmp, "out")
            TarGzExtractor.extract(src, dest)
            assertEquals(octal("755"), modeOf(joinPath(dest, "tool")))
        }

    private fun tarWithReadOnlyDir(dirFirst: Boolean) =
        TarWriter()
            .apply {
                if (dirFirst) dir("ro/", octal("555"))
                file("ro/file", bytes("hi"), octal("644"))
                if (!dirFirst) dir("ro/", octal("555"))
            }.finish()
}
