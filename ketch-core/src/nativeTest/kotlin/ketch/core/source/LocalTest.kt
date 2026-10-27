package ketch.core.source

import fs.createDirAll
import fs.entryKind
import fs.setMode
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
import ketch.core.testing.read
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
            val (a, b) = Path(tmp, "a") to Path(tmp, "b")
            for (dir in listOf(a, b)) {
                createDirAll(Path(dir, "Contents"))
                Path(dir, "Contents/Info.plist").write("x")
            }
            assertEquals(sha256Tree(a), sha256Tree(b))
        }

    @Test
    fun aChangedFileChangesTheTreeHash(): Unit =
        withTempDir { dir ->
            Path(dir, "a").write("one")
            val first = sha256Tree(dir)
            Path(dir, "a").write("two")
            assertNotEquals(first, sha256Tree(dir))
        }

    /** `a` = `X` and `b` = `Y` used to hash the same as one file `a` = `Xb\0Y`, because the record had no lengths. */
    @Test
    fun twoFilesDoNotHashLikeOneFileThatEmbedsTheSeparator(): Unit =
        withTempDir { tmp ->
            val split = Path(tmp, "split")
            createDirAll(split)
            Path(split, "a").write("X")
            Path(split, "b").write("Y")
            val fused = Path(tmp, "fused")
            createDirAll(fused)
            Path(fused, "a").write("Xb\u0000Y")
            assertNotEquals(sha256Tree(split), sha256Tree(fused))
        }

    @Test
    fun aModeChangeChangesTheTreeHash(): Unit =
        withTempDir { dir ->
            val file = Path(dir, "a")
            file.write("same")
            val first = sha256Tree(dir)
            setMode(file, "755".toInt(8))
            assertNotEquals(first, sha256Tree(dir))
        }

    @Test
    fun aSymlinkIsHashedByItsTargetAndNotAsAFile(): Unit =
        withTempDir { tmp ->
            fun tree(
                name: String,
                fill: (Path) -> Unit,
            ): String {
                val dir = Path(tmp, name)
                createDirAll(dir)
                fill(dir)
                return sha256Tree(dir)
            }
            val linked = tree("linked") { fs.symlink(Path("target-a"), Path(it, "a")) }
            assertNotEquals(linked, tree("as-file") { Path(it, "a").write("target-a") })
            assertNotEquals(linked, tree("empty") {})
            assertNotEquals(linked, tree("retargeted") { fs.symlink(Path("target-b"), Path(it, "a")) })
            assertEquals(linked, tree("again") { fs.symlink(Path("target-a"), Path(it, "a")) })
        }

    /** The digest a Rust ketch records for the same tree, so a lock written by either one verifies with the other. */
    @Test
    fun theTreeHashIsTheFramingRustWrites(): Unit =
        withTempDir { dir ->
            Path(dir, "a").write("X")
            setMode(Path(dir, "a"), "644".toInt(8))
            val record =
                byteArrayOf('f'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 0, 0, 1, 0xa4.toByte(), 0, 0, 0, 0, 0, 0, 0, 1) +
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
            val bin = Path(dir, "tool")
            bin.write("#!/bin/sh\necho hi\n")
            assertEquals(LocalKind.Binary, classify(bin))
        }

    @Test
    fun classifiesASymlink(): Unit =
        withTempDir { dir ->
            val bin = Path(dir, "tool")
            bin.write("#!/bin/sh\necho hi\n")
            val link = Path(dir, "link")
            fs.symlink(bin, link)
            assertEquals(LocalKind.Symlink, classify(link))
        }

    /** Windows place stages via copyTree after extract may have created real symlinks; materialise writes the target bytes. */
    @Test
    fun materializeLocalSymlinkWritesTheTargetFile(): Unit =
        withTempDir { dir ->
            Path(dir, "tool.exe").write("MZ")
            val link = Path(dir, "tool")
            fs.symlink(Path("tool.exe"), link)
            val out = Path(dir, "tool.materialised")
            materializeLocalSymlink(link, out)
            assertEquals("MZ", out.read())
            assertEquals(fs.EntryKind.File, entryKind(out))
        }

    @Test
    fun materializeLocalSymlinkErrorsWhenTargetIsMissing(): Unit =
        withTempDir { dir ->
            val link = Path(dir, "dangling")
            fs.symlink(Path("missing.bin"), link)
            val err = assertFailsWith<KetchError> { materializeLocalSymlink(link, Path(dir, "out")) }
            assertTrue("cannot materialise local symlink" in err.message, err.message)
        }

    @Test
    fun refusesAPlainDirectory(): Unit =
        withTempDir { dir ->
            val nested = Path(dir, "stuff")
            createDirAll(nested)
            assertTrue("directory" in assertFailsWith<KetchError> { classify(nested) }.message)
        }

    @Test
    fun classifiesAnAppBundle(): Unit =
        withTempDir { dir ->
            val app = Path(dir, "Thing.app")
            createDirAll(Path(app, "Contents/MacOS"))
            assertEquals(LocalKind.App, classify(app))
        }

    @Test
    fun refusesANamedPipeBeforeOpenCanBlock(): Unit =
        withTempDir { dir ->
            val fifo = Path(dir, "pipe")
            assertEquals(0, platform.posix.mkfifo(fifo.toString(), "600".toInt(8).toUShort()), "mkfifo failed")
            val err = assertFailsWith<KetchError> { classify(fifo) }.message
            assertTrue("not a regular file" in err, "unexpected error: $err")
        }

    @Test
    fun absolutePathResolvesRelative() {
        assertEquals(Path(currentDir()!!, "rel/tool"), absolutePath("rel/tool"))
        assertEquals(Path("/tmp/ketch-local-abs-tool"), absolutePath("/tmp/ketch-local-abs-tool"))
    }

    @Test
    fun onlyGithubHasDrafts() {
        assertTrue(GitHubSource(Http(null, Report.silent()), DEFAULT_API).supportsDrafts())
        assertFalse(LocalSource().supportsDrafts())
    }
}
