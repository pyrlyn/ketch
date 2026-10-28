package ketch.core.source

import fs.createDirAll
import fs.entryKind
import fs.setMode
import ketch.core.error.KetchError
import ketch.core.model.LocalKind
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalUnixTest {
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

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun refusesANamedPipeBeforeOpenCanBlock(): Unit =
        withTempDir { dir ->
            val fifo = Path(dir, "pipe")
            assertEquals(0, platform.posix.mkfifo(fifo.toString(), "600".toInt(8).convert()), "mkfifo failed")
            val err = assertFailsWith<KetchError> { classify(fifo) }.message
            assertTrue("not a regular file" in err, "unexpected error: $err")
        }
}
