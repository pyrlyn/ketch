// The install lock held across two commands, and `extra_paths` travelling with the package from install to uninstall
// (Rust tests/lock_extras.rs).
package ketch.e2e

import fs.createDirAll
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.okEnv
import ketch.e2e.support.resolve
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.SystemFileSystem
import platform.posix.getppid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockExtrasTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun publishTool(
        name: String,
        version: String,
    ) {
        val native = sandbox.asset(nativeName(name, version), tarGz(Entry.program("$name-$version/bin/$name", "$name $version")))
        sandbox.publish(name, listOf(Release(version, listOf(native))))
    }

    /** Two `upgrade` runs cannot both hold the lock: the second fails with the holder's pid (exit 8). */
    @Test
    fun aSecondUpgradeWhileTheLockIsHeldReportsTheHolder() {
        publishTool("testtool", "1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        // A live foreign pid: ketch's own would be adopted as a re-entrant lock instead of failing. The process that
        // started this test outlives it.
        writeText(sandbox.root.resolve(".lock"), "${getppid()}")

        val out = sandbox.ketch("upgrade", "--yes")
        assertFalse(out.success, "upgrade succeeded under a held lock")
        assertEquals(8, out.code)
        assertTrue("another ketch process holds the lock" in out.stderr, "wrong lock message:\n${out.stderr}")
    }

    /** `extra_paths` travel with the package: install links the man page and completion, uninstall takes them back. */
    @Test
    fun extrasAreLinkedOnInstallAndRemovedOnUninstall() {
        val native =
            sandbox.asset(
                nativeName("extratool", "1.0.0"),
                tarGz(
                    Entry.program("extratool-1.0.0/bin/extratool", "extratool 1.0.0"),
                    Entry.file("extratool-1.0.0/doc/extratool.1", ".TH EXTRATOOL 1\n"),
                    Entry.file("extratool-1.0.0/complete/extratool.bash", "# completion\n"),
                ),
            )
        sandbox.publish("extratool", listOf(Release("1.0.0", listOf(native))))
        writeText(
            sandbox.root.resolve("manifests/extratool.toml"),
            "name = \"extratool\"\nsource = \"test:extratool\"\nextra_paths = [\"doc/extratool.1\", \"complete/extratool.bash\"]\n",
        )

        // Man pages and completions live under the data home; without this the test writes into the runner's home.
        val dataHome = sandbox.home.resolve("data")
        createDirAll(dataHome)
        val env = mapOf("XDG_DATA_HOME" to dataHome.toString())
        sandbox.okEnv(listOf("install", "extratool", "--yes"), env)

        val man = dataHome.resolve("man/man1/extratool.1")
        val completion = dataHome.resolve("bash-completion/completions/extratool")
        assertTrue(SystemFileSystem.exists(man), "man page not linked at $man")
        assertTrue(SystemFileSystem.exists(completion), "completion not linked at $completion")

        sandbox.okEnv(listOf("uninstall", "extratool", "--yes"), env)
        assertFalse(SystemFileSystem.exists(man), "man page left behind at $man")
        assertFalse(SystemFileSystem.exists(completion), "completion left behind at $completion")
    }
}
