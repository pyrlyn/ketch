package ketch.core.extract

import archive.ArchiveException
import archive.TarWriter
import archive.ZipWriter
import archive.bzip2
import archive.gzip
import archive.gzipSource
import fs.bytesIfExists
import fs.joinPath
import ketch.core.testing.withTempDir
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveTest {
    private fun machoMatch32BitOnly(head: ByteArray) = head.size >= 4 && head.take(4).map { it.toUByte().toInt() } in listOf(listOf(0xfe, 0xed, 0xfa, 0xce), listOf(0xce, 0xfa, 0xed, 0xfe))

    private fun head(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun detectionSeparatesTarGzFromALoneGz(): Unit =
        withTempDir { tmp ->
            val tarball = joinPath(tmp, "a.gz")
            write(tarball, tarGzWith(Triple("x", bytes("y"), octal("644"))))
            var head = readHead(tarball)
            assertTrue(TarGzExtractor.detect(tarball, head))
            assertFalse(GzFileExtractor.detect(tarball, head))

            val plain = joinPath(tmp, "jq.gz")
            write(plain, gzip(bytes("\u007fELF and then some payload")))
            head = readHead(plain)
            assertFalse(TarGzExtractor.detect(plain, head))
            assertTrue(GzFileExtractor.detect(plain, head))
        }

    @Test
    fun xzPlaintextArrivesInTheCallersBuffer(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "payload.xz")
            write(src, XZ_ZS)
            val got = Buffer()
            xzReader(SystemFileSystem.source(src)).use { decoder ->
                val chunk = Buffer()
                while (true) {
                    val n = decoder.readAtMostTo(chunk, 32)
                    if (n == -1L) break
                    assertTrue(n <= 32)
                    got.write(chunk, n)
                }
            }
            assertContentEquals(ByteArray(64 * 1024) { 'z'.code.toByte() }, got.readByteArray())
        }

    @Test
    fun anXzDictionaryOverTheMemoryLimitIsRefused(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "payload.xz")
            write(src, XZ_HELLO)
            xzReader(SystemFileSystem.source(src), memLimit = 1024).use { decoder ->
                val error = assertFailsWith<ArchiveException> { decoder.readAtMostTo(Buffer(), 16) }
                assertEquals(ArchiveException.Kind.MemoryLimit, error.kind)
            }
        }

    @Test
    fun aTarXzExtractsThroughTheStreamingDecoder(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "tool.tar.xz")
            write(src, TAR_XZ_TOOL)
            val dest = dir(tmp, "out")
            TarXzExtractor.extract(src, dest)
            assertContentEquals(bytes("payload"), bytesIfExists(joinPath(dest, "bin/tool")))
        }

    @Test
    fun detectionSeparatesTarXzFromALoneXz(): Unit =
        withTempDir { tmp ->
            val tarball = joinPath(tmp, "a.xz")
            write(tarball, TAR_XZ_XY)
            var head = readHead(tarball)
            assertTrue(TarXzExtractor.detect(tarball, head))
            assertFalse(XzFileExtractor.detect(tarball, head))

            val plain = joinPath(tmp, "jq.xz")
            write(plain, XZ_ELF_LONG)
            head = readHead(plain)
            assertFalse(TarXzExtractor.detect(plain, head))
            assertTrue(XzFileExtractor.detect(plain, head))
        }

    @Test
    fun aLoneXzFileIsExtractedAsTheProgram(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "jq.xz")
            write(src, XZ_ELF)
            val dest = dir(tmp, "out")
            XzFileExtractor.extract(src, dest)
            assertContentEquals(bytes("\u007fELF payload"), bytesIfExists(joinPath(dest, "jq")))
        }

    @Test
    fun detectionSeparatesTarBz2FromALoneBz2(): Unit =
        withTempDir { tmp ->
            val tarball = joinPath(tmp, "a.bz2")
            write(tarball, bzip2(TarWriter().apply { file("x", bytes("y"), octal("644")) }.finish()))
            var head = readHead(tarball)
            assertTrue(TarBz2Extractor.detect(tarball, head))
            assertFalse(Bz2FileExtractor.detect(tarball, head))

            val plain = joinPath(tmp, "jq.bz2")
            write(plain, bzip2(bytes("\u007fELF payload")))
            head = readHead(plain)
            assertFalse(TarBz2Extractor.detect(plain, head))
            assertTrue(Bz2FileExtractor.detect(plain, head))
        }

    @Test
    fun aLoneGzipBombStopsAtTheBudget(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "tool.gz")
            write(src, gzip(ByteArray(4 shl 20)))
            val dest = dir(tmp, "out")
            val message =
                refusal {
                    gzipSource(SystemFileSystem.source(src)).use { extractLoneFile(it, src, dest, ".gz", Budget(1L shl 20, 10)) }
                }
            assertTrue("unpacks to more than" in message, message)
            val written = sizeOf(joinPath(dest, "tool"))
            assertTrue(written <= 1 shl 20, "wrote $written bytes past the budget")
        }

    @Test
    fun aTarMemberLargerThanTheBudgetIsRefusedBeforeItIsWritten(): Unit =
        withTempDir { dest ->
            val archive = TarWriter().apply { file("big", ByteArray(2 shl 20), octal("644")) }.finish()
            val message = refusal { unpackTar(source(archive), dest, Budget(1L shl 20, 10)) }
            assertTrue("unpacks to more than" in message, message)
            assertFalse(exists(joinPath(dest, "big")))
        }

    @Test
    fun aTarWithTooManyMembersIsRefused(): Unit =
        withTempDir { dest ->
            val archive = TarWriter().apply { repeat(20) { file("f$it", ByteArray(0), octal("644")) } }.finish()
            val message = refusal { unpackTar(source(archive), dest, Budget(Long.MAX_VALUE, 10)) }
            assertTrue("more than 10 entries" in message, message)
        }

    @Test
    fun aZipWithTooManyMembersIsRefused(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "many.zip")
            write(src, ZipWriter().apply { repeat(20) { file("f$it", ByteArray(0)) } }.finish())
            val dest = dir(tmp, "out")
            val message = refusal { unpackZip(src, dest, Budget(Long.MAX_VALUE, 10)) }
            assertTrue("more than 10 entries" in message, message)
        }

    @Test
    fun aZipBombStopsAtTheBudget(): Unit =
        withTempDir { tmp ->
            val src = joinPath(tmp, "bomb.zip")
            write(src, ZipWriter().apply { file("zeros", ByteArray(4 shl 20)) }.finish())
            val dest = dir(tmp, "out")
            val message = refusal { unpackZip(src, dest, Budget(1L shl 20, 10)) }
            assertTrue("unpacks to more than" in message, message)
            val written = sizeOf(joinPath(dest, "zeros"))
            assertTrue(written <= 1 shl 20, "wrote $written bytes past the budget")
        }

    @Test
    fun theBudgetScalesWithTheArchiveBetweenAFloorAndACeiling() {
        assertEquals(256L shl 20, Budget.forCompressedLen(1).limitBytes)
        assertEquals(32L shl 30, Budget.forCompressedLen(1L shl 30).limitBytes)
        assertEquals(1000L shl 20, Budget.forCompressedLen(10L shl 20).limitBytes)
        assertEquals(32L shl 30, Budget.forCompressedLen(Long.MAX_VALUE).limitBytes)
    }

    @Test
    fun aCompressedFileIsNeverTakenForABareProgram() {
        val path = Path("tool")
        for (magic in listOf(GZIP_MAGIC, XZ_MAGIC, BZ2_MAGIC, ZIP_MAGIC)) {
            assertFalse(RawBinaryExtractor.detect(path, magic + bytes("rest of the stream")), magic.contentToString())
        }
        assertTrue(RawBinaryExtractor.detect(path, byteArrayOf(0x7f) + bytes("ELF\u0002\u0001\u0001")))
        assertTrue(RawBinaryExtractor.detect(path, bytes("#!/bin/sh\n")))
    }

    @Test
    fun recognisesProgramHeaders() {
        assertTrue(isProgramHead(bytes("#!/bin/sh")))
        assertTrue(isProgramHead(head('M'.code, 'Z'.code, 0x90, 0x00)))
        // On-disk Mach-O prefixes: the byte order in each label is the order written in the file, not the spelling
        // of the u32 constant.
        val machoOnDisk =
            listOf(
                "Mach-O 64 BE" to head(0xfe, 0xed, 0xfa, 0xcf, 0, 0),
                "Mach-O 64 LE" to head(0xcf, 0xfa, 0xed, 0xfe, 0, 0),
                "Mach-O 32 BE" to head(0xfe, 0xed, 0xfa, 0xce, 0, 0),
                "Mach-O 32 LE" to head(0xce, 0xfa, 0xed, 0xfe, 0, 0),
            )
        for ((label, bytes) in machoOnDisk) {
            assertTrue(isProgramHead(bytes), label)
            if ("64" in label) assertFalse(machoMatch32BitOnly(bytes), "$label must not be covered by 32-bit Mach-O detection alone")
        }
        // Universal/fat headers use their own on-disk prefixes too.
        assertTrue(isProgramHead(head(0xca, 0xfe, 0xba, 0xbe, 0, 0)))
        assertTrue(isProgramHead(head(0xbe, 0xba, 0xfe, 0xca, 0, 0)))
        assertFalse(machoMatch32BitOnly(head(0xca, 0xfe, 0xba, 0xbe, 0, 0)))
        assertTrue(isProgramHead(head(0x7f, 'E'.code, 'L'.code, 'F'.code, 2)))
        assertFalse(isProgramHead(bytes("# Readme\n")))
        assertFalse(isProgramHead(bytes("plain text, not a program\n")))
        assertFalse(isProgramHead(ByteArray(0)))
        // A near-miss must not be treated as Mach-O just because it shares a prefix.
        assertFalse(isProgramHead(head(0xfe, 0xed, 0xfa, 0xcd, 0, 0)))
    }
}
