// A broken source plugin is a warning, never a fatal error, and a scheme with no plugin at all is an actionable error,
// not a hang (Rust tests/plugin_fail.rs). Plugins are written into the sandbox, so discovery takes the production path.
package ketch.e2e

import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.resolve
import ketch.e2e.support.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginFailTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun writePlugin(
        name: String,
        body: String,
    ) = sandbox.root.resolve("plugins/$name").also {
        writeText(it, body)
        makeExecutable(it)
    }

    /** `capabilities` is how a plugin introduces itself; one that exits non-zero there is broken and must be named. */
    @Test
    fun aPluginThatFailsCapabilitiesIsNamedByPluginList() {
        writePlugin("ketch-source-broken", "#!/bin/sh\necho 'boom' >&2\nexit 1\n")

        val out = sandbox.ketch("plugin", "list")
        assertTrue(out.success, "$out")
        assertTrue("no plugins" in out.stdout || "broken" in out.stderr, "broken plugin neither listed nor warned about:\n$out")
    }

    /** A plugin on a protocol ketch does not know is ignored: its scheme must not resolve, and the error names it. */
    @Test
    fun aPluginOnAFutureProtocolDoesNotProvideItsScheme() {
        writePlugin(
            "ketch-source-future",
            "#!/bin/sh\ncase \"\$1\" in capabilities) echo '{\"protocol\":9999,\"scheme\":\"future\"}';; *) exit 1;; esac\n",
        )

        val out = sandbox.ketch("install", "future:owner/repo", "--yes")
        assertFalse(out.success, "install through an unsupported protocol succeeded")
        assertTrue("future" in out.stderr || "ketch-source-future" in out.stderr, "error names neither scheme nor plugin:\n${out.stderr}")
    }

    /** A plugin whose `releases` exits non-zero fails the install, with its stderr attached, not swallowed. */
    @Test
    fun aPluginThatFailsReleasesFailsTheInstallWithItsStderr() {
        writePlugin(
            "ketch-source-flaky",
            "#!/bin/sh\ncase \"\$1\" in capabilities) echo '{\"protocol\":1,\"scheme\":\"flaky\"}';; " +
                "releases) echo 'flaky says no' >&2; exit 1;; *) exit 1;; esac\n",
        )

        val out = sandbox.ketch("install", "flaky:owner/repo", "--yes")
        assertFalse(out.success, "install through failing releases succeeded")
        assertTrue("flaky" in out.stderr, "error does not name the failing plugin:\n${out.stderr}")
    }
}
