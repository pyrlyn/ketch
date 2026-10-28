// `ketch config reset`, which writes `config.toml` with the compiled defaults after backing up the existing file
// (Rust tests/config_reset.rs).
package ketch.e2e

import fs.readText
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigResetTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun backups() = sandbox.names(sandbox.root).filter { it.startsWith("config.toml.bak-") }

    @Test
    fun resetWritesDefaultsAndBacksUpTheOldFile() {
        sandbox.configure("prerelease = true\n")
        val before = sandbox.root.resolve("config.toml")
        assertTrue(SystemFileSystem.exists(before))

        val out = sandbox.ketch("config", "reset", "--yes")
        assertTrue(out.success, "reset failed: ${out.stderr}")

        val body = readText(before)
        assertTrue("Written by `ketch config reset`" in body, body)
        assertTrue("auto_update = true" in body, body)
        assertTrue("emoji = true" in body, body)
        assertEquals(1, backups().size, "one backup beside the reset file")
        assertEquals("prerelease = true\n", readText(sandbox.root.resolve(backups()[0])))

        // A second reset with changed bytes (the defaults now, vs the old backup) backs up again; a third finds the
        // defaults already backed up and stops.
        assertTrue(sandbox.ketch("config", "reset", "--yes").success)
        assertEquals(2, backups().size, "changed bytes are copied again")
        assertTrue(sandbox.ketch("config", "reset", "--yes").success)
        assertEquals(2, backups().size, "identical bytes are not copied again")
    }

    @Test
    fun resetWithoutAFileWritesDefaultsAndNoBackup() {
        val path = sandbox.root.resolve("config.toml")
        assertFalse(SystemFileSystem.exists(path))

        sandbox.ok("config", "reset", "--yes")

        val body = readText(path)
        assertTrue("jobs = 4" in body, body)
        assertTrue(backups().isEmpty(), "a missing file is not copied")
    }

    @Test
    fun resetAsksFirstWithoutYes() {
        sandbox.configure("prerelease = true\n")

        // Non-interactive stdin answers with the default (no), so the file stays.
        assertTrue(sandbox.ketch("config", "reset").success)
        assertEquals("prerelease = true\n", readText(sandbox.root.resolve("config.toml")))
    }
}
