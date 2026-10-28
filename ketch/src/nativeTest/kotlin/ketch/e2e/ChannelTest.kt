// The release channel an installed package follows, through the real binary (Rust tests/channel.rs). The channel is
// recorded at install and read back by `outdated`, `upgrade` and `info` in later runs, so only a sequence of separate
// invocations shows whether it survives.
package ketch.e2e

import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelTest {
    private val sandbox = Sandbox().also { writeText(it.root.resolve("manifests/chanpkg.toml"), "name = \"chanpkg\"\nsource = \"test:chanpkg\"\n") }

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun release(version: String) = Release(version, listOf(sandbox.asset(nativeName("chanpkg", version), tarGz(Entry.program("chanpkg-$version/bin/chanpkg", "chanpkg $version")))))

    private fun outdatedLatest() = json(sandbox.ok("outdated", "--json"))["outdated"].items.map { it["latest"].str }

    private fun channel() = json(sandbox.ok("info", "chanpkg", "--json"))["channel"].str

    @Test
    fun aPackageInstalledWithPreKeepsFollowingPrereleases() {
        sandbox.publish("chanpkg", listOf(release("2.0.0-rc.1").intoPrerelease(), release("1.0.0")))
        sandbox.ok("install", "chanpkg", "--pre", "-y")
        assertEquals("pre", channel())

        sandbox.publish("chanpkg", listOf(release("2.0.0-rc.2").intoPrerelease(), release("2.0.0-rc.1").intoPrerelease(), release("1.0.0")))
        assertEquals(listOf("2.0.0-rc.2"), outdatedLatest())

        sandbox.publish("chanpkg", listOf(release("2.0.0"), release("2.0.0-rc.2").intoPrerelease()))
        sandbox.ok("upgrade", "chanpkg", "-y")
        assertEquals("2.0.0", json(sandbox.ok("info", "chanpkg", "--json"))["installed"].str)
        // Landing on a stable release is not leaving the channel.
        assertEquals("pre", channel())

        sandbox.publish("chanpkg", listOf(release("2.1.0-rc.1").intoPrerelease(), release("2.0.0")))
        assertEquals(listOf("2.1.0-rc.1"), outdatedLatest())
    }

    @Test
    fun aPackageInstalledWithoutPreStaysOnStableReleases() {
        sandbox.publish("chanpkg", listOf(release("2.0.0-rc.1").intoPrerelease(), release("1.0.0")))
        sandbox.ok("install", "chanpkg", "-y")
        assertEquals("stable", channel())
        assertFalse("\"channel\"" in sandbox.state(), "stable is the default and stays out of state.json")
        assertTrue(outdatedLatest().isEmpty())
    }
}
