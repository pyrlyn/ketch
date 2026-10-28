// When the real binary paints its status lines, and when it must not (Rust tests/colour.rs). What only the binary can
// show is the decision: CLICOLOR_FORCE, NO_COLOR, --no-color and a pipe reach the ui through the environment and the
// argument parser. `uninstall` of a package never installed is the failure used throughout: offline, touches nothing.
package ketch.e2e

import fs.TempDir
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.runOnce
import process.Command
import process.envVars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColourTest {
    /** Run `ketch uninstall ghost` in a throwaway root and home, with nothing colour-related inherited from the shell. */
    private fun uninstallGhost(
        extraArgs: List<String>,
        envs: Map<String, String>,
    ): String =
        TempDir.create("ketch-e2e").use { root ->
            TempDir.create("ketch-e2e").use { home ->
                val cmd = Command(KETCH_EXE, extraArgs + listOf("uninstall", "ghost"))
                envVars().map { it.first }.filter { it.startsWith("KETCH_") }.forEach(cmd::envRemove)
                cmd
                    .env("KETCH_ROOT", root.path.toString())
                    .env("KETCH_AUTO_UPDATE", "false")
                    .env("HOME", home.path.toString())
                    .envRemove("NO_COLOR")
                    .envRemove("CLICOLOR_FORCE")
                    .envRemove("GITHUB_TOKEN")
                    .envRemove("GH_TOKEN")
                envs.forEach { (k, v) -> cmd.env(k, v) }
                val out = cmd.runOnce()
                assertFalse(out.success, "uninstalling nothing must fail")
                out.stderr
            }
        }

    @Test
    fun aForcedErrorLineIsRedEndToEnd() {
        val stderr = uninstallGhost(emptyList(), mapOf("CLICOLOR_FORCE" to "1"))
        assertEquals("\\e[31mghost: not found\\e[0m", stderr.lines().first().replace("\u001b", "\\e"))
    }

    @Test
    fun aPipeGetsNoEscapeBytes() {
        val stderr = uninstallGhost(emptyList(), emptyMap())
        assertFalse('\u001b' in stderr, stderr)
        assertTrue("ghost: not found" in stderr, stderr)
    }

    @Test
    fun noColorKeepsEscapeBytesOut() {
        val stderr = uninstallGhost(emptyList(), mapOf("NO_COLOR" to "1"))
        assertFalse('\u001b' in stderr, stderr)
    }

    @Test
    fun theNoColorFlagOutranksAForcedColour() {
        val stderr = uninstallGhost(listOf("--no-color"), mapOf("CLICOLOR_FORCE" to "1"))
        assertFalse('\u001b' in stderr, stderr)
    }

    @Test
    fun theNoEmojiFlagIsAcceptedEverywhereAndAPipeGetsNoIcon() {
        val stderr = uninstallGhost(listOf("--no-emoji"), mapOf("KETCH_EMOJI" to "1"))
        assertTrue(stderr.startsWith("ghost: not found"), stderr)
        assertFalse("❌" in stderr, stderr)
    }
}
