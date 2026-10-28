package ketch.core.source

import fs.createDirAll
import fs.joinPath
import fs.setMode
import ketch.core.config.HOST_WINDOWS
import ketch.core.config.currentDir
import ketch.core.error.KetchError
import ketch.core.extract.BZ2_MAGIC
import ketch.core.extract.GZIP_MAGIC
import ketch.core.extract.XZ_MAGIC
import ketch.core.extract.ZIP_MAGIC
import ketch.core.extract.looksLikeTar
import ketch.core.http.Http
import ketch.core.model.LocalKind
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalTest {
    @Test
    fun twoIdenticalTreesHashTheSame(): Unit =
        withTempDir { tmp ->
            val (a, b) = joinPath(tmp, "a") to joinPath(tmp, "b")
            for (dir in listOf(a, b)) {
                createDirAll(joinPath(dir, "Contents"))
                joinPath(dir, "Contents/Info.plist").write("x")
            }
            assertEquals(sha256Tree(a), sha256Tree(b))
        }

    @Test
    fun aChangedFileChangesTheTreeHash(): Unit =
        withTempDir { dir ->
            joinPath(dir, "a").write("one")
            val first = sha256Tree(dir)
            joinPath(dir, "a").write("two")
            assertNotEquals(first, sha256Tree(dir))
        }

    /** `a` = `X` and `b` = `Y` used to hash the same as one file `a` = `Xb\0Y`, because the record had no lengths. */
    @Test
    fun twoFilesDoNotHashLikeOneFileThatEmbedsTheSeparator(): Unit =
        withTempDir { tmp ->
            val split = joinPath(tmp, "split")
            createDirAll(split)
            joinPath(split, "a").write("X")
            joinPath(split, "b").write("Y")
            val fused = joinPath(tmp, "fused")
            createDirAll(fused)
            joinPath(fused, "a").write("Xb\u0000Y")
            assertNotEquals(sha256Tree(split), sha256Tree(fused))
        }

    /** The digest a Rust ketch records for the same tree, so a lock written by either one verifies with the other. */
    @Test
    fun theTreeHashIsTheFramingRustWrites(): Unit =
        withTempDir { dir ->
            joinPath(dir, "a").write("X")
            setMode(joinPath(dir, "a"), "644".toInt(8))
            // Windows keeps only the read-only attribute, which Rust hashes as 0o666 or 0o444.
            val mode = if (HOST_WINDOWS) 0xb6 else 0xa4
            val record =
                byteArrayOf('f'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 0, 0, 1, mode.toByte(), 0, 0, 0, 0, 0, 0, 0, 1) +
                    "X".encodeToByteArray()
            assertEquals(
                ketch.core.hash.Sha256
                    .hex(record),
                sha256Tree(dir),
            )
        }

    @Test
    fun archiveBytesMatchTheExtractorMagics() {
        assertTrue(looksLikeArchive(GZIP_MAGIC))
        assertTrue(looksLikeArchive(XZ_MAGIC))
        assertTrue(looksLikeArchive(BZ2_MAGIC))
        assertTrue(looksLikeArchive(ZIP_MAGIC))
        val tar = ByteArray(262)
        "ustar".encodeToByteArray().copyInto(tar, 257)
        assertTrue(looksLikeTar(tar))
        assertTrue(looksLikeArchive(tar))
        assertFalse(looksLikeArchive("#!/bin/sh\n".encodeToByteArray()))
    }

    @Test
    fun classifiesABareBinary(): Unit =
        withTempDir { dir ->
            val bin = joinPath(dir, "tool")
            bin.write("#!/bin/sh\necho hi\n")
            assertEquals(LocalKind.Binary, classify(bin))
        }

    @Test
    fun refusesAPlainDirectory(): Unit =
        withTempDir { dir ->
            val nested = joinPath(dir, "stuff")
            createDirAll(nested)
            assertTrue("directory" in assertFailsWith<KetchError> { classify(nested) }.message)
        }

    @Test
    fun classifiesAnAppBundle(): Unit =
        withTempDir { dir ->
            val app = joinPath(dir, "Thing.app")
            createDirAll(joinPath(app, "Contents/MacOS"))
            assertEquals(LocalKind.App, classify(app))
        }

    @Test
    fun absolutePathResolvesRelative() {
        assertEquals(joinPath(currentDir()!!, "rel/tool"), absolutePath("rel/tool"))
        val abs = if (HOST_WINDOWS) "C:\\ketch-local-abs-tool" else "/tmp/ketch-local-abs-tool"
        assertEquals(Path(abs), absolutePath(abs))
    }

    @Test
    fun onlyGithubHasDrafts() {
        assertTrue(GitHubSource(Http(null, Report.silent()), DEFAULT_API).supportsDrafts())
        assertFalse(LocalSource().supportsDrafts())
    }
}
