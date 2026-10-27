@file:OptIn(ExperimentalForeignApi::class)

package ketch.core.manifest

import fs.atomic
import fs.entryKind
import fs.mode
import fs.readText
import ketch.core.error.KetchError
import ketch.core.testing.withTempDir
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserManifestTest {
    private fun <T> userFile(
        body: String,
        test: (dir: Path, path: Path) -> T,
    ): T =
        withTempDir { dir ->
            val path = Path(dir, "rtok.toml")
            atomic(path, body.encodeToByteArray())
            test(dir, path)
        }

    @Test
    fun writeBinsAddsTheEntryAndKeepsEveryOtherByte(): Unit =
        userFile(
            "# my rtok, pinned to the fork\n" +
                "name   = \"rtok\"   # spaced on purpose\n" +
                "source = \"github:me/rtok\"\n" +
                "\n" +
                "[asset]\n" +
                "include = [\"*.tar.xz\"]\n",
        ) { _, path ->
            assertTrue(writeBins(path, "rtok", listOf("rtok-cli")))
            val written = readText(path)
            val expected =
                "# my rtok, pinned to the fork\n" +
                    "name   = \"rtok\"   # spaced on purpose\n" +
                    "source = \"github:me/rtok\"\n" +
                    "bin = [{ name = \"rtok-cli\" }]\n" +
                    "\n" +
                    "[asset]\n" +
                    "include = [\"*.tar.xz\"]\n"
            assertEquals(expected, written)
            assertEquals("rtok-cli", parseRegistry(written, "test")[0].bin[0].name)
        }

    @Test
    fun writeBinsWritesOneEntryPerNameInOrder(): Unit =
        userFile("name = \"rtok\"\nsource = \"github:me/rtok\"\n") { _, path ->
            assertTrue(writeBins(path, "rtok", listOf("rtok", "other-tool")))
            val written = readText(path)
            assertTrue(written.endsWith("bin = [{ name = \"rtok\" }, { name = \"other-tool\" }]\n"), written)
        }

    @Test
    fun writeBinsLeavesAManifestThatAlreadyNamesOneAlone() {
        val body = "name = \"rtok\"\nsource = \"github:me/rtok\"\nbin = [{ name = \"rtok\" }]\n"
        userFile(body) { _, path ->
            assertFalse(writeBins(path, "rtok", listOf("rtok-cli")))
            assertEquals(body, readText(path))
        }
    }

    @Test
    fun writeBinsFindsThePackageInAMultiPackageFile(): Unit =
        userFile(
            "[[package]]\nname = \"other\"\nsource = \"github:me/other\"\n\n[[package]]\nname = \"rtok\"\nsource = \"github:me/rtok\"\n",
        ) { _, path ->
            assertTrue(writeBins(path, "rtok", listOf("rtok-cli")))
            val parsed = parseRegistry(readText(path), "t")
            assertTrue(parsed[0].bin.isEmpty())
            assertEquals("rtok-cli", parsed[1].bin[0].name)
        }

    @Test
    fun writeBinsInAMultiPackageFileKeepsEveryOtherByte() {
        val body =
            "# two of mine\n" +
                "[[package]]\n" +
                "source = \"github:me/other\"   # source first, on purpose\n" +
                "name = \"other\"\n" +
                "\n" +
                "# the fork\n" +
                "[[package]]\n" +
                "name = \"RTok\"\n" +
                "source = \"github:me/rtok\"\n" +
                "description = \"after source\"\n"
        userFile(body) { _, path ->
            assertTrue(writeBins(path, "rtok", listOf("rtok-cli")))
            assertEquals("${body}bin = [{ name = \"rtok-cli\" }]\n", readText(path))
        }
    }

    @Test
    fun writeBinsEditsTheTargetOfASymlinkedManifest(): Unit =
        userFile("name = \"rtok\"\nsource = \"github:me/rtok\"\n") { dir, target ->
            val link = Path(dir, "link.toml")
            check(symlink(target.toString(), link.toString()) == 0)
            assertTrue(writeBins(link, "rtok", listOf("rtok-cli")))
            assertEquals(fs.EntryKind.Symlink, entryKind(link))
            assertTrue("rtok-cli" in readText(target))
        }

    @Test
    fun writeManifestCreatesTheDirectoryAndTheFile(): Unit =
        withTempDir { dir ->
            val path = Path(Path(dir, "manifests"), "fly.toml")
            val body = "name = \"fly\"\nsource = \"github:superfly/flyctl\"\n"
            writeManifest(path, body)
            assertEquals(body, readText(path))
        }

    @Test
    fun writeManifestRefusesTextThatIsNotAManifestAndWritesNothing(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "fly.toml")
            assertFailsWith<KetchError> { writeManifest(path, "name = [\n") }
            assertNull(entryKind(path))
        }

    @Test
    fun writeManifestReplacesAnOlderCopyWhole(): Unit =
        userFile("name = \"rtok\"\nsource = \"github:me/rtok\"\n") { _, path ->
            val body = "name = \"rtok\"\nsource = \"github:me/rtok-fork\"\n"
            writeManifest(path, body)
            assertEquals(body, readText(path))
        }

    @Test
    fun aManifestKeepsItsModeAndANewOneIsPrivate(): Unit =
        withTempDir { dir ->
            val shared = Path(dir, "shared.toml")
            atomic(shared, "old".encodeToByteArray())
            chmod(shared.toString(), "644".toInt(8).convert())
            replaceFile(shared, "new")
            assertEquals("644".toInt(8), mode(shared))

            val fresh = Path(dir, "fresh.toml")
            replaceFile(fresh, "new")
            assertEquals("600".toInt(8), mode(fresh))
        }
}
