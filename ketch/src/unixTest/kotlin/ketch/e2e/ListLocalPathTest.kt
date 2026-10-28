// `ketch list` for a package installed from a local path (Rust tests/list.rs, the `cfg(unix)` case: its fixture is a
// shell script).
package ketch.e2e

import ketch.e2e.support.Sandbox
import ketch.e2e.support.get
import ketch.e2e.support.json
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.ok
import ketch.e2e.support.writeText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListLocalPathTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    @Test
    fun aPackageInstalledFromALocalPathIsListedWithoutALatest() {
        val fixture = sandbox.fixture("pathtool")
        writeText(fixture, "#!/bin/sh\necho path\n")
        makeExecutable(fixture)
        sandbox.ok("install", "--path", fixture.toString(), "--yes")

        // Nothing to ask is not the same as nobody answering.
        val out = sandbox.ok("list")
        assertTrue(row(out, "pathtool").startsWith('*'), out)
        assertFalse("offline" in out, out)
        assertFalse('?' in out, out)
        val parsed = json(sandbox.ok("list", "--json"))
        assertEquals(JsonNull, parsed["packages"][0]["latest"], "$parsed")
        assertEquals(JsonArray(emptyList()), parsed["unreachable"])
    }
}
