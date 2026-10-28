// `ketch uninstall` of a name that is not installed: one line per name, exit 4, nothing removed
// (Rust tests/uninstall_not_found.rs).
package ketch.e2e

import fs.createDirAll
import fs.isDir
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.resolve
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UninstallNotFoundTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    @Test
    fun aNameThatIsNotInstalledPrintsOneLineAndExits4() {
        val out = sandbox.ketch("uninstall", "nope", "--yes")

        assertEquals(4, out.code)
        assertEquals("nope: not found\n", out.stderr)
        assertTrue(out.stdout.isEmpty())
    }

    @Test
    fun everyMissingNameIsReported() {
        val out = sandbox.ketch("uninstall", "one", "two", "--yes")

        assertEquals(4, out.code)
        assertEquals("one: not found\ntwo: not found\n", out.stderr)
    }

    @Test
    fun aStoreFolderWithNoRecordIsRemovedAndStillNotFound() {
        val leftover = sandbox.store.resolve("ghost")
        createDirAll(leftover.resolve("1.0.0.old"))

        val out = sandbox.ketch("uninstall", "ghost", "--yes")

        assertEquals(4, out.code)
        assertEquals("ghost: not found\n", out.stderr)
        assertFalse(SystemFileSystem.exists(leftover), "the leftover folder survived")
    }

    @Test
    fun aNameThatDiffersOnlyInCaseNeverRemovesTheRealFolder() {
        val installed = sandbox.store.resolve("ghost")
        createDirAll(installed.resolve("1.0.0"))

        // On a case-insensitive file system `store/GHOST` is `store/ghost`.
        val out = sandbox.ketch("uninstall", "GHOST", "--yes")

        assertEquals(4, out.code)
        assertEquals("GHOST: not found\n", out.stderr)
        assertTrue(isDir(installed.resolve("1.0.0")), "the real folder was removed")
    }

    @Test
    fun aLeftoverFolderIsKeptUnlessTheRemovalIsConfirmed() {
        val leftover = sandbox.store.resolve("ghost")
        createDirAll(leftover.resolve("1.0.0.old"))

        val out = sandbox.ketch("uninstall", "ghost")

        assertEquals(4, out.code)
        assertTrue("ghost: not found" in out.stderr, out.stderr)
        assertTrue(isDir(leftover), "removed without confirmation")
    }
}
