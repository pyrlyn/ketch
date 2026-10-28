// Windows pipeline coverage: the real binary against a throwaway root (Rust tests/install_windows.rs).
package ketch.e2e

import fs.EntryKind
import fs.bytesIfExists
import fs.createDirAll
import fs.entryKind
import fs.isDir
import ketch.e2e.support.Archive
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.hostArch
import ketch.e2e.support.ketch
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import process.Command
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallWindowsTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun toolArchive(version: String) =
        Archive.Zip(
            listOf(
                Entry.program("testtool-$version/bin/testtool", "testtool $version"),
                Entry.file("testtool-$version/README.md", "# testtool\n"),
            ),
        )

    private fun publishTool(version: String) {
        val native = sandbox.asset("testtool-$version-${hostArch()}-pc-windows-msvc.zip", toolArchive(version))
        val linux = sandbox.asset("testtool-$version-${hostArch()}-unknown-linux-gnu.tar.gz", toolArchive("linux-decoy"))
        sandbox.publish("testtool", listOf(Release(version, listOf(linux, native))))
    }

    private val installedBin get() = sandbox.bin.resolve("testtool.cmd")

    private fun run(path: Path): String {
        val out = Command(path.toString(), emptyList()).runOnce()
        assertTrue(out.success, "$path did not run: $out")
        return out.stdout.trim()
    }

    @Test
    fun aToolIsDownloadedVerifiedCopiedAndRunnable() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        val dest = installedBin
        assertEquals(EntryKind.File, entryKind(dest), "windows placement must copy, not symlink: $dest")
        assertEquals("testtool 1.0.0", run(dest))

        val listed = sandbox.state()
        assertTrue(""""name": "testtool"""" in listed, listed)
        assertTrue(""""checksum_verified": true""" in listed, listed)
        assertTrue("testtool-1.0.0-${hostArch()}-pc-windows-msvc.zip" in listed, listed)
    }

    @Test
    fun anUpgradeReplacesThePayloadAndTheCopyStillWorks() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        assertEquals("testtool 1.0.0", run(installedBin))

        publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        assertEquals("testtool 2.0.0", run(installedBin))
        assertTrue(isDir(sandbox.store.resolve("testtool").resolve("1.0.0")), "upgrade must keep the previous prefix")
    }

    @Test
    fun rollbackRestoresThePreviousPrefixWithoutRedownloading() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        publishTool("2.0.0")
        sandbox.ok("rollback", "testtool")
        assertEquals("testtool 1.0.0", run(installedBin))
    }

    @Test
    fun rollbackWithoutARetainedVersionLeavesTheInstallAlone() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        val stderr = sandbox.fails("rollback", "testtool")
        assertTrue("no retained version" in stderr, stderr)
    }

    @Test
    fun rollbackRefusesAPinnedPackage() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        sandbox.ok("pin", "testtool")
        val stderr = sandbox.fails("rollback", "testtool")
        assertTrue("pinned" in stderr.lowercase(), stderr)
    }

    @Test
    fun uninstallAfterRollbackRemovesEveryRetainedPrefix() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool@1.0.0", "--yes")
        publishTool("2.0.0")
        sandbox.ok("upgrade", "--yes")
        sandbox.ok("rollback", "testtool")
        sandbox.ok("uninstall", "testtool", "--yes")
        assertFalse(SystemFileSystem.exists(sandbox.store.resolve("testtool")))
    }

    @Test
    fun aDownloadThatDoesNotMatchItsChecksumInstallsNothing() {
        val asset = sandbox.asset("testtool-1.0.0-${hostArch()}-pc-windows-msvc.zip", toolArchive("1.0.0")).withWrongDigest()
        sandbox.publish("testtool", listOf(Release("1.0.0", listOf(asset))))
        sandbox.fails("install", "test:testtool", "--yes")
        assertFalse(SystemFileSystem.exists(installedBin))
    }

    @Test
    fun aBinaryTheUserPutThereIsNeverOverwritten() {
        publishTool("1.0.0")
        createDirAll(sandbox.bin)
        writeText(installedBin, "mine")
        val err = sandbox.fails("install", "test:testtool", "--yes")
        assertTrue("already exists" in err, err)
        assertContentEquals("mine".encodeToByteArray(), bytesIfExists(installedBin))
    }

    @Test
    fun uninstallRemovesEveryTraceOfATool() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        sandbox.ok("uninstall", "testtool", "--yes")
        assertFalse(SystemFileSystem.exists(installedBin))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    @Test
    fun doctorReportsWritableDirsWithoutMacosOutput() {
        val out = sandbox.ok("doctor")
        assertTrue("writable" in out, out)
        assertFalse("macos only" in out.lowercase(), out)
        assertFalse(".app" in out, out)
    }

    /**
     * `ketch path install` writes HKCU\Environment\Path, not a shell file. The sandbox bin dir is unique, so a leftover
     * is a dead temp path; the `finally` still takes it back out if the assertions fail.
     */
    @Test
    fun pathInstallPutsTheBinDirOnTheUserPath() {
        try {
            sandbox.ok("path", "install")
            val status = sandbox.ok("path", "status")
            assertTrue(status.lines().any { "user PATH" in it && "configured" in it }, "user PATH should be configured:\n$status")

            sandbox.ok("path", "uninstall")
            val after = sandbox.ok("path", "status")
            assertTrue(after.lines().any { "user PATH" in it && "not set up" in it }, "user PATH should be empty of this bin dir:\n$after")
        } finally {
            sandbox.ketch("path", "uninstall")
        }
    }

    /**
     * `self uninstall` takes the bin dir back out of HKCU\Environment\Path even when the entry is not spelled the way
     * ketch spells the bin dir: `install.ps1` writes whatever `Resolve-Path` gave it.
     */
    @Test
    fun selfUninstallRemovesTheUserPathEntryHoweverItIsSpelled() {
        val root = sandbox.root
        try {
            val spelled = "\"${sandbox.bin.toString().uppercase()}\\\""
            val before = userPath()
            setUserPath(if (before.isEmpty()) spelled else "$spelled;$before")
            assertEquals(listOf(spelled), entriesUnder(root))

            sandbox.ok("self", "uninstall", "--yes")
            assertTrue(entriesUnder(root).isEmpty(), "the user PATH still names the bin dir: ${userPath()}")
        } finally {
            val left = entriesUnder(root)
            if (left.isNotEmpty()) setUserPath(userPath().split(';').filter { it !in left }.joinToString(";"))
        }
    }

    private fun powershell(
        script: String,
        env: Pair<String, String>? = null,
    ) = Command("powershell", listOf("-NoProfile", "-NonInteractive", "-Command", script))
        .let { cmd -> env?.let { cmd.env(it.first, it.second) } ?: cmd }
        .runOnce()

    private fun userPath() = powershell("[Environment]::GetEnvironmentVariable('Path','User')").stdout.trim()

    private fun setUserPath(value: String) {
        val out = powershell("[Environment]::SetEnvironmentVariable('Path', \$env:TEST_USER_PATH, 'User')", "TEST_USER_PATH" to value)
        assertTrue(out.success, "could not write the user PATH: $out")
    }

    /** Entries of the user PATH that name anything under [root], however spelled. */
    private fun entriesUnder(root: Path): List<String> {
        val prefix = root.toString().lowercase()
        return userPath().split(';').filter {
            it
                .trim('"')
                .replace('/', '\\')
                .lowercase()
                .startsWith(prefix)
        }
    }
}
