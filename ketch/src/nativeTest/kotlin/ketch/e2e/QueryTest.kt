// Regression tests for read-only query commands (Rust tests/query.rs).
package ketch.e2e

import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.ketch
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CHANGELOG = "# Changelog\n\n## [2.0.0] - 2024-06-01\n\n- the second one\n\n## [1.0.0] - 2024-05-01\n\n- the first one\n"

class QueryTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun publishTool(version: String) {
        val native =
            sandbox.asset(
                nativeName("testtool", version),
                tarGz(Entry.program("testtool-$version/bin/testtool", "testtool $version"), Entry.file("testtool-$version/CHANGELOG.md", CHANGELOG)),
            )
        sandbox.publish("testtool", listOf(Release(version, listOf(native)).withNotes("published notes for $version")))
    }

    private fun publishNamed(
        name: String,
        version: String,
    ) {
        val asset = sandbox.asset(nativeName(name, version), tarGz(Entry.program("$name-$version/bin/$name", "$name $version")))
        sandbox.publish(name, listOf(Release(version, listOf(asset))))
    }

    /** `@version` picks the changelog section; it does not mean "not installed". */
    @Test
    fun changelogFileWithAnExplicitVersionReadsTheInstalledPayload() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")

        val out = sandbox.ok("changelog", "testtool@1.0.0", "--file")
        assertTrue("the first one" in out, "no 1.0.0 section:\n$out")
        assertFalse("the second one" in out, "ran past 1.0.0 into 2.0.0:\n$out")
    }

    /** Installed state is the answer even when the source plugin is gone. */
    @Test
    fun infoStillReportsAnInstalledPackageWhenTheSourceIsUnavailable() {
        publishTool("1.0.0")
        sandbox.ok("install", "test:testtool", "--yes")
        SystemFileSystem.delete(sandbox.root.resolve("plugins/ketch-source-test"))

        val info = sandbox.ok("info", "testtool")
        assertTrue("installed" in info, info)
        assertTrue("1.0.0" in info, info)
        assertTrue(sandbox.bin.resolve("testtool").toString() in info, "link missing:\n$info")

        val parsed = json(sandbox.ok("info", "testtool", "--json"))
        assertEquals("1.0.0", parsed["installed"].str)
        assertEquals("testtool", parsed["name"].str)
    }

    /** Scripts need to tell a partial check from "everything current". */
    @Test
    fun outdatedJsonReportsPartialFailuresAndStillExitsNonZero() {
        publishNamed("alpha", "1.0.0")
        publishNamed("bravo", "1.0.0")
        sandbox.ok("install", "test:alpha", "test:bravo", "--yes")

        publishNamed("alpha", "2.0.0")
        sandbox.unpublish("bravo")

        val out = sandbox.ketch("outdated", "--json")
        assertFalse(out.success, "outdated --json passed when a check failed")
        val parsed = json(out.stdout)
        assertEquals("partial", parsed["status"].str, out.stdout)
        assertTrue(parsed["outdated"].items.any { it["name"].str == "alpha" }, out.stdout)
        assertTrue(parsed["failed"].items.any { it["name"].str == "bravo" }, out.stdout)
        assertTrue("bravo" in out.stderr, out.stderr)
    }
}
