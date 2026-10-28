// Linux pipeline coverage: the real binary against a throwaway root (Rust tests/install_linux.rs).
package ketch.e2e

import fs.EntryKind
import fs.bytesIfExists
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.readText
import fs.removeFile
import ketch.e2e.support.Archive
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.hostArch
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallLinuxTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private val tool get() = sandbox.bin.resolve("testtool")

    private fun toolArchive(version: String): Archive =
        tarGz(
            Entry.program("testtool-$version/bin/testtool", "testtool $version"),
            Entry.file("testtool-$version/README.md", "# testtool\n"),
        )

    private fun linuxTool(version: String) = "testtool-$version-${hostArch()}-unknown-linux-gnu.tar.gz"

    private fun publishTool(version: String) {
        val native = sandbox.asset(linuxTool(version), toolArchive(version))
        val macos = sandbox.asset("testtool-$version-${hostArch()}-apple-darwin.tar.gz", toolArchive("macos-decoy"))
        sandbox.publish("testtool", listOf(Release(version, listOf(macos, native))))
    }

    private fun upgradedFromOne() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
    }

    @Test
    fun aToolIsDownloadedVerifiedLinkedAndRunnable() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        assertEquals(EntryKind.Symlink, entryKind(tool), "expected a symlink at $tool")
        assertEquals("testtool 1.0.0", said(tool))

        val listed = sandbox.state()
        assertTrue("\"name\": \"testtool\"" in listed, listed)
        assertTrue("\"checksum_verified\": true" in listed, listed)
        assertTrue(linuxTool("1.0.0") in listed, listed)
    }

    @Test
    fun anUpgradeReplacesThePayloadAndTheLinkStillWorks() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        assertEquals("testtool 1.0.0", said(tool))

        publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 2.0.0", said(tool))
        assertTrue(isDir(sandbox.store.resolve("testtool/1.0.0")), "upgrade must keep the previous prefix")
    }

    @Test
    fun rollbackRestoresThePreviousPrefixWithoutRedownloading() {
        upgradedFromOne()
        publishTool("2.0.0")
        sandbox.ok("rollback", "testtool")
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun rollbackWithoutARetainedVersionLeavesTheInstallAlone() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val err = sandbox.fails("rollback", "testtool")
        assertTrue("no retained version" in err, err)
    }

    @Test
    fun rollbackRefusesAPinnedPackage() {
        upgradedFromOne()
        sandbox.ok("pin", "testtool")
        val err = sandbox.fails("rollback", "testtool")
        assertTrue("pinned" in err.lowercase(), err)
    }

    @Test
    fun uninstallAfterRollbackRemovesEveryRetainedPrefix() {
        upgradedFromOne()
        sandbox.ok("rollback", "testtool")
        sandbox.ok("uninstall", "testtool", "--yes")
        assertFalse(SystemFileSystem.exists(sandbox.store.resolve("testtool")))
    }

    @Test
    fun aDownloadThatDoesNotMatchItsChecksumInstallsNothing() {
        val asset = sandbox.asset(linuxTool("1.0.0"), toolArchive("1.0.0")).withWrongDigest()
        sandbox.publish("testtool", listOf(Release("1.0.0", listOf(asset))))
        sandbox.fails("install", "test:testtool", "--yes")
        assertFalse(SystemFileSystem.exists(tool))
    }

    @Test
    fun aBinaryTheUserPutThereIsNeverOverwritten() {
        publishTool("1.0.0")
        createDirAll(sandbox.bin)
        writeText(tool, "mine")
        val err = sandbox.fails("install", "test:testtool", "--yes")
        assertTrue("already exists" in err, err)
        assertContentEquals("mine".encodeToByteArray(), bytesIfExists(tool))
    }

    @Test
    fun relinkRebuildsALinkThatWasDeletedByHand() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        removeFile(tool)
        sandbox.ok("link", "testtool")
        assertEquals("testtool 1.0.0", said(tool))
    }

    @Test
    fun uninstallRemovesEveryTraceOfATool() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("uninstall", "testtool", "--yes")
        assertFalse(SystemFileSystem.exists(tool))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    @Test
    fun doctorReportsAHealthyTree() {
        val out = sandbox.ok("doctor")
        assertTrue("writable" in out, out)
        assertFalse("macos only" in out.lowercase(), out)
    }

    @Test
    fun pathInstallEditsAShellConfigAndCanUndoItself() {
        sandbox.ok("path", "install", "--shell", "bash")
        val bashrc = sandbox.home.resolve(".bashrc")
        assertTrue("# >>> ketch >>>" in readText(bashrc), readText(bashrc))
        sandbox.ok("path", "uninstall", "--shell", "bash")
        assertFalse("# >>> ketch >>>" in readText(bashrc), readText(bashrc))
    }
}
