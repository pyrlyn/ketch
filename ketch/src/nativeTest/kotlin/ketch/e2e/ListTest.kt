// `ketch list` in its three modes: `local`, `remote`, and both at once (Rust tests/list.rs). The real binary against
// the offline test plugin and a registry written into the sandbox, so `latest` comes from the production lookup.
// Colour is off (the sandbox sets NO_COLOR) except in the one test that forces it on.
//
// The standard fixture, used by most tests:
//
// | package | registry | installed      | published      |
// |---------|----------|----------------|----------------|
// | alpha   | yes      | 1.0.0          | 1.0.0, 2.0.0   |
// | bravo   | no       | 1.0.0          | 1.0.0          |
// | charlie | yes      | 1.0.0, pinned  | 1.0.0, 1.5.0   |
// | delta   | yes      | no             | 3.1.0          |
package ketch.e2e

import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.ketch
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.okEnv
import ketch.e2e.support.resolve
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import snapshot.SnapshotSettings
import snapshot.assertSnapshot
import snapshot.withSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    /** Serve [versions] of [id], each with an asset this machine can install. */
    private fun publish(
        id: String,
        vararg versions: String,
    ) = sandbox.publish(
        id,
        versions.map { version ->
            Release(version, listOf(sandbox.asset(nativeName(id, version), tarGz(Entry.program("$id-$version/bin/$id", "$id $version")))))
        },
    )

    private fun registry(
        name: String,
        description: String,
    ) = sandbox.registryPackage(name, "name = \"$name\"\nsource = \"test:$name\"\ndescription = \"$description\"\n")

    /** The fixture in the table at the top of this file. */
    private fun standard() {
        registry("alpha", "The first tool")
        registry("charlie", "A tool held at one version")
        registry("delta", "Not installed yet")
        publish("alpha", "1.0.0")
        publish("bravo", "1.0.0")
        publish("charlie", "1.0.0")
        publish("delta", "3.1.0")
        sandbox.ok("install", "alpha", "test:bravo", "charlie", "--yes")
        sandbox.ok("pin", "charlie")
        publish("alpha", "1.0.0", "2.0.0")
        publish("charlie", "1.0.0", "1.5.0")
    }

    private fun keys(value: JsonElement) = (value as JsonObject).keys.sorted()

    private fun row(
        out: String,
        name: String,
    ) = out.lines().firstOrNull { line -> line.split(' ').filter(String::isNotEmpty).any { it == name } } ?: error("no row for $name:\n$out")

    private fun words(line: String) = line.split(' ').filter(String::isNotEmpty)

    private fun names(packages: JsonElement) = packages.items.map { it["name"].str }

    private fun snapshot(
        name: String,
        text: String,
        expression: String,
    ) = withSettings(SnapshotSettings(prefix = "list", source = "tests/list.rs")) { assertSnapshot(name, text, expression = expression) }

    @Test
    fun listLocalShowsInstalledPackagesWithTheirNotes() {
        standard()
        snapshot("local", sandbox.ok("list", "local"), "sandbox.ok(&[\"list\", \"local\"])")
    }

    @Test
    fun listRemoteShowsTheRegistryWithLatestVersions() {
        standard()
        snapshot("remote", sandbox.ok("list", "remote"), "sandbox.ok(&[\"list\", \"remote\"])")
    }

    @Test
    fun bareListShowsInstalledAndAvailablePackagesInOneTable() {
        standard()
        snapshot("all", sandbox.ok("list"), "sandbox.ok(&[\"list\"])")
    }

    @Test
    fun jsonOfEachModeHasExactlyTheDocumentedFields() {
        standard()

        val local = sandbox.ok("list", "local", "--json")
        for (entry in json(local).items) assertEquals(listOf("channel", "installed", "name", "pinned", "retained", "source"), keys(entry))
        snapshot("local-json", local, "local")

        val remote = sandbox.ok("list", "remote", "--json")
        for (entry in json(remote).items) assertEquals(listOf("description", "latest", "name", "source"), keys(entry))
        snapshot("remote-json", remote, "remote")

        val all = sandbox.ok("list", "--json")
        val parsed = json(all)
        assertEquals(listOf("packages", "unreachable"), keys(parsed))
        for (entry in parsed["packages"].items) {
            assertEquals(listOf("channel", "installed", "latest", "name", "pinned", "source", "update_available"), keys(entry))
        }
        snapshot("all-json", all, "all")
    }

    @Test
    fun bareListMarksInstalledPackagesAndShowsBothVersionsOnlyForThem() {
        standard()
        val out = sandbox.ok("list")
        assertTrue(row(out, "alpha").startsWith("*  alpha    1.0.0"), out)
        assertTrue(row(out, "bravo").startsWith('*'), out)
        assertTrue(row(out, "charlie").startsWith('*'), out)
        val delta = row(out, "delta")
        assertTrue(delta.startsWith("   delta"), out)
        assertEquals(listOf("delta", "3.1.0", "test:delta"), words(delta), out)
        assertFalse('●' in out, out)
    }

    @Test
    fun colourForcedOnMarksInstalledPackagesWithADotAndABoldName() {
        standard()
        val out = sandbox.okEnv(listOf("list"), mapOf("CLICOLOR_FORCE" to "1"))
        assertTrue("●  \u001b[1malpha\u001b[0m" in out, out)
        assertTrue("2.0.0 \u001b[33m(update available)\u001b[0m" in out, out)
        assertFalse("*  " in out, out)
        val delta = row(out, "delta")
        assertFalse('●' in delta, delta)
        assertFalse("\u001b[1m" in delta, delta)
    }

    @Test
    fun onlyAnInstalledUnpinnedPackageWithANewerReleaseIsAnUpdate() {
        standard()
        val out = sandbox.ok("list")
        assertTrue("2.0.0 (update available)" in row(out, "alpha"), out)
        for (name in listOf("bravo", "charlie", "delta")) assertFalse("update available" in row(out, name), out)
        assertEquals("1 update available: ketch upgrade alpha", out.lines().last { it.isNotEmpty() }, out)

        val parsed = json(sandbox.ok("list", "--json"))
        assertEquals(listOf("alpha"), parsed["packages"].items.filter { it["update_available"] == JsonPrimitive(true) }.map { it["name"].str })
    }

    @Test
    fun aPinnedPackageWithANewerReleaseSaysPinnedAndIsNotOffered() {
        standard()
        val out = sandbox.ok("list")
        val charlie = row(out, "charlie")
        assertTrue("1.0.0 (pinned)" in charlie, out)
        assertTrue("1.5.0" in charlie, out)
        assertFalse("update available" in charlie, out)
        assertFalse("ketch upgrade alpha charlie" in out, out)
    }

    @Test
    fun aPackageInstalledFromOutsideTheRegistryGetsLatestFromItsOwnSource() {
        standard()
        publish("bravo", "1.0.0", "1.2.0")
        val out = sandbox.ok("list")
        assertEquals(listOf("*", "bravo", "1.0.0", "1.2.0", "(update", "available)", "test:bravo"), words(row(out, "bravo")), out)
        assertFalse("bravo" in sandbox.ok("list", "remote"))
    }

    @Test
    fun offlineBareListPrintsTheLocalPartAndSaysSo() {
        standard()
        for (id in listOf("alpha", "bravo", "charlie", "delta")) sandbox.unpublish(id)
        assertEquals("${sandbox.ok("list", "local")}latest: offline\n", sandbox.ok("list"))

        val parsed = json(sandbox.ok("list", "--json"))
        assertEquals(listOf("alpha", "bravo", "charlie"), names(parsed["packages"]))
        assertTrue(parsed["packages"].items.all { it["latest"] == JsonNull })
    }

    @Test
    fun offlineListRemoteFailsWithAClearMessage() {
        standard()
        for (id in listOf("alpha", "charlie", "delta")) sandbox.unpublish(id)
        val err = sandbox.fails("list", "remote")
        assertTrue("could not reach any package source" in err, err)
        assertTrue("ketch list local" in err, err)
    }

    @Test
    fun oneUnreachablePackageIsAQuestionMarkNotAFailure() {
        standard()
        sandbox.unpublish("bravo")
        val out = sandbox.ok("list")
        assertEquals(listOf("*", "bravo", "1.0.0", "?", "test:bravo"), words(row(out, "bravo")), out)
        for (name in listOf("alpha", "charlie", "delta")) row(out, name)
        assertTrue("? means the latest release could not be checked: bravo" in out, out)
        assertFalse("offline" in out, out)

        val parsed = json(sandbox.ok("list", "--json"))
        assertEquals(JsonArray(listOf(JsonPrimitive("bravo"))), parsed["unreachable"])
        assertEquals(4, parsed["packages"].items.size)
    }

    @Test
    fun namesOnlyPrintsTheNamesOfEachMode() {
        standard()
        // No lookups are needed for names, so a source that cannot answer changes nothing here.
        sandbox.unpublish("delta")
        assertEquals("alpha\nbravo\ncharlie\n", sandbox.ok("list", "local", "--names-only"))
        assertEquals("alpha\ncharlie\ndelta\n", sandbox.ok("list", "remote", "--names-only"))
        assertEquals("alpha\nbravo\ncharlie\ndelta\n", sandbox.ok("list", "--names-only"))
    }

    @Test
    fun theHiddenInstalledFlagIsListLocal() {
        standard()
        for (extra in listOf(emptyList(), listOf("--json"), listOf("--names-only"))) {
            assertEquals(sandbox.ok(listOf("list", "local") + extra), sandbox.ok(listOf("list", "--installed") + extra), "$extra")
        }
        assertTrue("ketch list local" in sandbox.ketch("list", "--installed").stderr)
    }

    @Test
    fun emptyModesSayWhatIsMissing() {
        assertEquals("nothing installed\n", sandbox.ok("list", "local"))
        assertEquals("registry is empty; run ketch update\n", sandbox.ok("list", "remote"))
        assertEquals("nothing installed\nregistry is empty; run ketch update\n", sandbox.ok("list"))
        assertEquals("[]", sandbox.ok("list", "local", "--json").trim())
        assertEquals("[]", sandbox.ok("list", "remote", "--json").trim())
    }

    @Test
    fun latestVersionsAreCachedForAShortWhile() {
        standard()
        val cache = sandbox.root.resolve("cache/latest.json")
        assertTrue("3.1.0" in row(sandbox.ok("list"), "delta"))
        assertTrue(SystemFileSystem.metadataOrNull(cache)?.isRegularFile == true)

        // A release published a moment later is not asked about again yet, and a source that stopped answering is
        // still answered from the cache.
        publish("delta", "3.1.0", "3.2.0")
        sandbox.unpublish("alpha")
        val out = sandbox.ok("list")
        assertTrue("3.1.0" in row(out, "delta"), out)
        assertTrue("2.0.0" in row(out, "alpha"), out)

        // `ketch list local` never reads or needs it.
        SystemFileSystem.delete(cache)
        sandbox.ok("list", "local")
        assertFalse(SystemFileSystem.exists(cache))
    }

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
