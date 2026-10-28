// A ketch that `mise use -g github:pyrlyn/ketch` installed: the real binary, copied into a sandboxed mise tree and run
// from there (Rust tests/mise.rs). Copies installed before the move from listepo still live in `github-listepo-ketch`,
// and mise only forgets them under that name.
package ketch.e2e

import fs.readText
import ketch.e2e.support.MISE_TOOL_DIRS
import ketch.e2e.support.Sandbox
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiseTest {
    /** The tool name mise knows for the directory [tool]. */
    private fun miseTool(tool: String) = tool.replaceFirst('-', ':').replaceFirst('-', '/')

    @Test
    fun aMiseOwnedKetchWillNotRewriteItselfInPlace() =
        Sandbox().use { sandbox ->
            val exe = sandbox.installWithMise()
            val (miseBin, miseLog) = sandbox.fakeMise()

            val out = sandbox.ketchFrom(exe, listOf("self", "upgrade", "--yes"), miseBin)

            assertFalse(out.success, "the upgrade went ahead:\n${out.stderr}")
            assertTrue("managed by mise" in out.stderr, out.stderr)
            assertTrue("mise upgrade" in out.stderr, out.stderr)
            assertTrue(SystemFileSystem.exists(exe), "the mise-owned binary was touched")
            assertFalse(SystemFileSystem.exists(miseLog), "mise was run for an upgrade")
        }

    @Test
    fun selfUninstallHandsAMiseInstallBackToMise() {
        for (tool in MISE_TOOL_DIRS) {
            Sandbox().use { sandbox ->
                val exe = sandbox.installWithMiseAs(tool)
                val (miseBin, miseLog) = sandbox.fakeMise()

                val out = sandbox.ketchFrom(exe, listOf("self", "uninstall", "--yes"), miseBin)

                assertTrue(out.success, "$tool: uninstall failed:\n${out.stderr}")
                // `--yes` in front: the user has already answered, and mise would otherwise ask again for every
                // version it prunes.
                assertEquals("--yes unuse -g ${miseTool(tool)}", readText(miseLog))
                assertFalse("mise:" in out.stderr, "$tool: mise could not remove the install:\n${out.stderr}")
                assertFalse(SystemFileSystem.exists(sandbox.miseToolDir()), "$tool: the mise install survived:\n${out.stderr}")
                assertFalse(SystemFileSystem.exists(sandbox.root), "$tool: the root survived:\n${out.stderr}")
            }
        }
    }

    @Test
    fun aDryRunNamesTheMiseCommandAndRunsNothing() {
        for (tool in MISE_TOOL_DIRS) {
            Sandbox().use { sandbox ->
                val exe = sandbox.installWithMiseAs(tool)
                val (miseBin, miseLog) = sandbox.fakeMise()

                val out = sandbox.ketchFrom(exe, listOf("self", "uninstall", "--dry-run"), miseBin)

                assertTrue(out.success, "$tool: ${out.stderr}")
                assertTrue("mise unuse -g ${miseTool(tool)}" in out.stderr, "$tool: ${out.stderr}")
                assertFalse(SystemFileSystem.exists(miseLog), "$tool: mise was run on a dry run")
                assertTrue(SystemFileSystem.exists(exe))
                assertTrue(SystemFileSystem.exists(sandbox.root))
            }
        }
    }
}
