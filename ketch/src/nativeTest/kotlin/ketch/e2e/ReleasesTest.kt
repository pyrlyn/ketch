// `ketch releases`, `ketch info --releases` and the channel in `ketch list`, through the real binary and the offline
// test source (Rust tests/releases.rs). The kind of a release (stable, pre, dev) is derived from what the source
// reports, and "installed" from the recorded tag, so only an install followed by a listing shows the two agree.
package ketch.e2e

import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleasesTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun release(version: String) = Release(version, listOf(sandbox.asset(nativeName("relpkg", version), tarGz(Entry.program("relpkg-$version/bin/relpkg", "relpkg $version")))))

    /** Newest first, as a source reports them: a floating tag that is also flagged prerelease, a release candidate, and two stable releases. */
    @BeforeTest
    fun publish() {
        writeText(sandbox.root.resolve("manifests/relpkg.toml"), "name = \"relpkg\"\nsource = \"test:relpkg\"\n")
        sandbox.publish(
            "relpkg",
            listOf(
                release("nightly").withTag("nightly").published("2026-10-09T01:00:00Z").intoPrerelease(),
                release("2.0.0-rc.1").published("2026-09-01T00:00:00Z").intoPrerelease(),
                release("1.1.0").published("2026-03-04T05:06:07Z"),
                release("1.0.0").published("2026-01-02T00:00:00Z"),
            ),
        )
    }

    /** Whitespace-separated cells of each table row, header excluded. */
    private fun rows(out: String) =
        out
            .lines()
            .drop(1)
            .filter { it.isNotEmpty() }
            .map { line -> line.split(' ').filter(String::isNotEmpty) }

    private fun tags(value: JsonElement) = value.items.map { it["tag"].str }

    @Test
    fun releasesListsStableOnesNewestFirstAndMarksTheInstalledOne() {
        sandbox.ok("install", "relpkg@1.0.0", "-y")
        assertEquals(
            listOf(listOf("v1.1.0", "1.1.0", "2026-03-04", "stable"), listOf("*", "v1.0.0", "1.0.0", "2026-01-02", "stable")),
            rows(sandbox.ok("releases", "relpkg")),
        )
    }

    @Test
    fun preWidensTheListingToPrereleasesAndFloatingTags() {
        assertEquals(
            listOf(
                listOf("nightly", "-", "2026-10-09", "dev"),
                listOf("v2.0.0-rc.1", "2.0.0-rc.1", "2026-09-01", "pre"),
                listOf("v1.1.0", "1.1.0", "2026-03-04", "stable"),
                listOf("v1.0.0", "1.0.0", "2026-01-02", "stable"),
            ),
            rows(sandbox.ok("releases", "relpkg", "--pre")),
        )
    }

    @Test
    fun jsonNamesTheKindAndWhetherItIsInstalled() {
        sandbox.ok("install", "relpkg@1.0.0", "-y")
        val listed = json(sandbox.ok("releases", "relpkg", "--pre", "--json"))
        assertEquals(listOf("nightly", "v2.0.0-rc.1", "v1.1.0", "v1.0.0"), tags(listed))
        assertEquals(JsonNull, listed[0]["version"])
        assertEquals("dev", listed[0]["kind"].str)
        assertEquals("2026-10-09T01:00:00Z", listed[0]["published_at"].str)
        assertEquals("pre", listed[1]["kind"].str)
        assertEquals("1.0.0", listed[3]["version"].str)
        assertEquals(JsonPrimitive(true), listed[3]["installed"])
        assertEquals(JsonPrimitive(false), listed[2]["installed"])
    }

    @Test
    fun limitKeepsTheNewestReleases() {
        assertEquals(listOf("nightly", "v2.0.0-rc.1"), tags(json(sandbox.ok("releases", "relpkg", "--pre", "--json", "-n", "2"))))
        assertTrue(sandbox.fails("releases", "relpkg", "-n", "0").isNotEmpty())
    }

    @Test
    fun aPackageInstalledOnThePreChannelListsPrereleasesWithoutPre() {
        sandbox.ok("install", "relpkg", "--pre", "-y")
        assertEquals(listOf("nightly", "v2.0.0-rc.1", "v1.1.0", "v1.0.0"), tags(json(sandbox.ok("releases", "relpkg", "--json"))))
    }

    @Test
    fun anUnknownPackageIsAnError() {
        assertTrue(sandbox.fails("releases", "no-such-package").isNotEmpty())
    }

    @Test
    fun infoReleasesPrintsTheSameListUnderTheDetails() {
        sandbox.ok("install", "relpkg@1.0.0", "-y")

        val listing = sandbox.ok("releases", "relpkg")
        val info = sandbox.ok("info", "relpkg", "--releases")
        assertTrue(info.startsWith("relpkg\n"), info)
        assertTrue("installed" in info, info)
        assertTrue(info.endsWith(listing), "info --releases ends with the releases table:\n$info\nvs\n$listing")
        assertFalse("published" in sandbox.ok("info", "relpkg"), "without the flag there is no table")

        assertEquals(listOf("v1.1.0", "v1.0.0"), tags(json(sandbox.ok("info", "relpkg", "--releases", "--json"))["releases"]))
        assertEquals(JsonNull, json(sandbox.ok("info", "relpkg", "--json"))["releases"])
    }

    @Test
    fun listShowsTheChannelOfAPackageThatIsNotStable() {
        sandbox.ok("install", "relpkg", "--pre", "-y")

        val local = sandbox.ok("list", "local")
        assertTrue("(pre)" in local, "the channel follows the version:\n$local")
        assertEquals("pre", json(sandbox.ok("list", "local", "--json"))[0]["channel"].str)
        assertEquals("pre", json(sandbox.ok("list", "--json"))["packages"][0]["channel"].str)

        sandbox.ok("install", "relpkg@1.0.0", "--force", "-y")
        assertEquals("stable", json(sandbox.ok("list", "local", "--json"))[0]["channel"].str)
        assertFalse("(pre)" in sandbox.ok("list", "local"))
    }

    @Test
    fun listPreLooksForPrereleasesInTheLatestColumn() {
        sandbox.ok("install", "relpkg@1.0.0", "-y")

        fun latest(vararg args: String) = json(sandbox.ok(*args))["packages"][0]["latest"].str
        assertEquals("1.1.0", latest("list", "--json"))
        // A floating tag is never "the newest": it has no version to compare.
        assertEquals("2.0.0-rc.1", latest("list", "--pre", "--json"))
    }

    @Test
    fun theVersionColumnIsTheNormalisedVersionNotTheTagAgain() {
        // A GitHub release's version is read from its tag, `v` and all.
        sandbox.publish("relpkg", listOf(release("v3.0.0").withTag("v3.0.0")))
        assertEquals(listOf(listOf("v3.0.0", "3.0.0", "-", "stable")), rows(sandbox.ok("releases", "relpkg")))
        assertEquals("3.0.0", json(sandbox.ok("releases", "relpkg", "--json"))[0]["version"].str)
    }
}
