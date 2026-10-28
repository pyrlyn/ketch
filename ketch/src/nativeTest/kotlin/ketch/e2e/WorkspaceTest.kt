// The one check of Rust tests/workspace.rs that is about behaviour rather than Cargo metadata: the core and the
// binary report one version.
package ketch.e2e

import ketch.core.selfupdate.currentVersion
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ok
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    @Test
    fun theCoreReportsTheVersionTheBinaryWasBuiltAs() {
        val said = sandbox.ok("--version").trim()
        assertEquals(currentVersion().toString(), said.removePrefix("ketch ").substringBefore(' '), said)
    }
}
