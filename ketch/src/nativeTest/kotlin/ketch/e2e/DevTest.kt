// Dev builds on a floating tag, through the real binary (Rust tests/dev.rs). A `nightly` tag is republished under the
// same name, so what is installed is told apart from what the tag names now by the build, not the version. Only
// separate runs against a fixture that republishes between them show that the build recorded at install is the one
// `outdated`, `upgrade` and `sync` compare against later.
package ketch.e2e

import fs.readText
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
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val FIRST = "2026-10-01T00:00:00Z"
private const val SECOND = "2026-10-09T00:00:00Z"

class DevTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    @BeforeTest
    fun manifest() = writeText(sandbox.root.resolve("manifests/devpkg.toml"), "name = \"devpkg\"\nsource = \"test:devpkg\"\n")

    private fun nightly(says: String) =
        Release("nightly", listOf(sandbox.asset(nativeName("devpkg", "nightly"), tarGz(Entry.program("devpkg-nightly/bin/devpkg", says)))))
            .withTag("nightly")

    private fun stable() = Release("1.0.0", listOf(sandbox.asset(nativeName("devpkg", "1.0.0"), tarGz(Entry.program("devpkg-1.0.0/bin/devpkg", "devpkg 1.0.0")))))

    /** The `nightly` tag pointing at [build], beside a stable `v1.0.0`. The asset keeps its name, so a republish replaces its bytes. */
    private fun publishNightly(
        build: String,
        published: String,
    ) = sandbox.publish("devpkg", listOf(nightly("devpkg $build").published(published), stable()))

    private fun installed() = json(sandbox.state())["packages"]["devpkg"]

    private fun outdated() = json(sandbox.ok("outdated", "--json"))["outdated"].items

    /** Whether `ketch list` offers devpkg an update, asked afresh: the latest cache would otherwise answer from the last listing. */
    private fun listedAsUpdate(): Boolean {
        runCatching { SystemFileSystem.delete(sandbox.root.resolve("cache/latest.json")) }
        val all = json(sandbox.ok("list", "--json"))
        val row = all["packages"].items.firstOrNull { it["name"].str == "devpkg" } ?: error("devpkg is listed: $all")
        assertEquals("nightly", row["latest"].str, "$row")
        return row["update_available"] == JsonPrimitive(true)
    }

    @Test
    fun aRepublishedFloatingTagIsAnUpgradeAndAStableReleaseIsNot() {
        publishNightly("one", FIRST)
        sandbox.ok("install", "devpkg@nightly", "-y")

        val info = json(sandbox.ok("info", "devpkg", "--json"))
        assertEquals("dev", info["channel"].str, "$info")
        val first = installed()
        assertEquals("nightly", first["tag"].str)
        assertEquals(FIRST, first["published_at"].str)
        // `v1.0.0` is newer by any version comparison, but the package follows its tag, not versions.
        assertEquals(emptyList(), outdated())
        assertFalse(listedAsUpdate())

        publishNightly("two", SECOND)
        val rows = outdated()
        assertEquals(1, rows.size, "$rows")
        assertEquals("devpkg", rows[0]["name"].str)
        assertTrue(listedAsUpdate(), "list agrees with outdated")

        sandbox.ok("upgrade", "-y")
        val second = installed()
        assertEquals("nightly", second["tag"].str)
        assertEquals("dev", second["channel"].str)
        assertEquals(SECOND, second["published_at"].str)
        assertNotEquals(first["sha256"], second["sha256"], "the new build is placed")
        assertEquals(emptyList(), outdated())
        assertFalse(listedAsUpdate())
    }

    @Test
    fun aSourceWithoutDraftsRefusesToListThem() {
        publishNightly("one", FIRST)
        val err = sandbox.fails("releases", "devpkg", "--draft")
        assertTrue("no draft releases" in err, err)
    }

    @Test
    fun installingTheTagAgainTakesItsNewBuildAndNotBefore() {
        publishNightly("one", FIRST)
        sandbox.ok("install", "devpkg@nightly", "-y")
        val err = sandbox.fails("install", "devpkg@nightly", "-y")
        assertTrue("already installed" in err, err)

        publishNightly("two", SECOND)
        sandbox.ok("install", "devpkg@nightly", "-y")
        assertEquals(SECOND, installed()["published_at"].str)
    }

    @Test
    fun aLockfileHoldsAFloatingTagToTheBuildItRecorded() {
        publishNightly("one", FIRST)
        sandbox.ok("install", "devpkg@nightly", "-y")
        val lock = sandbox.root.resolve("ketch.lock")
        sandbox.ok("lock", "--file", lock.toString())
        val text = readText(lock)
        assertTrue("published = \"$FIRST\"" in text, text)

        publishNightly("two", SECOND)
        sandbox.ok("uninstall", "devpkg", "-y")

        // Here the recorded hash is the pin.
        var err = sandbox.fails("sync", "--file", lock.toString(), "-y")
        assertTrue("does not match the lockfile" in err, err)
        assertEquals(JsonNull, installed())

        // Elsewhere the hash names another file, and the publication time is what is left to refuse the moved tag with.
        val target = text.lines().firstNotNullOf { it.removePrefix("target = ").takeIf { rest -> rest != it } }.trim('"')
        val other = if (target == "linux-x86_64") "linux-aarch64" else "linux-x86_64"
        writeText(lock, text.replace("target = \"$target\"", "target = \"$other\""))
        err = sandbox.fails("sync", "--file", lock.toString(), "-y")
        assertTrue("now names another build" in err, err)
        assertTrue("ketch lock" in err, err)
        assertEquals(JsonNull, installed())
    }

    @Test
    fun aDevInstallListsItsOwnNightlyEvenWhenItIsFlaggedPrerelease() {
        // A stable release beside it, or the source lists prereleases anyway.
        sandbox.publish("devpkg", listOf(nightly("devpkg one").published(FIRST).intoPrerelease(), stable()))
        sandbox.ok("install", "devpkg@nightly", "-y")

        val listed = json(sandbox.ok("releases", "devpkg", "--json"))
        assertTrue(
            listed.items.any { it["tag"].str == "nightly" && it["installed"] == JsonPrimitive(true) && it["kind"].str == "dev" },
            "$listed",
        )
    }
}
