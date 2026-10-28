@file:OptIn(ExperimentalForeignApi::class)

package ketch.core.manifest

import fs.atomic
import fs.entryKind
import fs.joinPath
import fs.mode
import fs.readText
import ketch.core.testing.withTempDir
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserManifestUnixTest {
    @Test
    fun writeBinsEditsTheTargetOfASymlinkedManifest(): Unit =
        userFile("name = \"rtok\"\nsource = \"github:me/rtok\"\n") { dir, target ->
            val link = joinPath(dir, "link.toml")
            check(symlink(target.toString(), link.toString()) == 0)
            assertTrue(writeBins(link, "rtok", listOf("rtok-cli")))
            assertEquals(fs.EntryKind.Symlink, entryKind(link))
            assertTrue("rtok-cli" in readText(target))
        }

    @Test
    fun aManifestKeepsItsModeAndANewOneIsPrivate(): Unit =
        withTempDir { dir ->
            val shared = joinPath(dir, "shared.toml")
            atomic(shared, "old".encodeToByteArray())
            chmod(shared.toString(), "644".toInt(8).convert())
            replaceFile(shared, "new")
            assertEquals("644".toInt(8), mode(shared))

            val fresh = joinPath(dir, "fresh.toml")
            replaceFile(fresh, "new")
            assertEquals("600".toInt(8), mode(fresh))
        }
}
