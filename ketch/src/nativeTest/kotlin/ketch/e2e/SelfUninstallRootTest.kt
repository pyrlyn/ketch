// `ketch self uninstall` run by the ketch that lives inside the root it is removing, which is where `ketch self
// install` puts it (Rust tests/self_uninstall_root.rs).
package ketch.e2e

import fs.copyFile
import fs.createDirAll
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.Sandbox
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.resolve
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfUninstallRootTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    @Test
    fun selfUninstallLeavesNoRootOnceTheRunningKetchHasExited() {
        val dir = sandbox.store.resolve("ketch/0.8.1")
        createDirAll(dir)
        val exe = dir.resolve("ketch")
        copyFile(Path(KETCH_EXE), exe)
        makeExecutable(exe)
        // A stale swap sibling, as a failed update leaves it.
        createDirAll(sandbox.store.resolve("ketch/0.8.0.old"))
        val nowhere = sandbox.home.resolve("no-extra-path")

        val out = sandbox.ketchFrom(exe, listOf("self", "uninstall", "--yes"), nowhere)
        assertTrue(out.success, "uninstall failed:\n${out.stderr}")
        // Only Windows hands the rest to a process that outlives this one; on unix the root is gone on exit.
        assertFalse(SystemFileSystem.exists(sandbox.root), "the root survived:\n${out.stderr}")
    }
}
