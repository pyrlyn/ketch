// Binary-level smoke tests for stable, script-visible CLI behaviour (Rust tests/cli.rs). The install suite owns the
// pipeline; these stay platform-neutral so argument parsing and output contracts are checked anywhere.
package ketch.e2e

import fs.TempDir
import fs.isDir
import ketch.core.VERSION
import ketch.core.selfupdate.VERSION_CHANNEL
import ketch.e2e.support.ketchBare
import ketch.e2e.support.resolve
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliTest {
    private val expected = "$VERSION · $VERSION_CHANNEL"
    private val temp = TempDir.create("ketch-e2e")

    @AfterTest
    fun cleanUp() = temp.close()

    @Test
    fun versionMatchesThePackageVersionMarkedPreview() {
        val out = ketchBare("--version")
        assertTrue(out.success, "$out")
        assertEquals("", out.stderr)
        assertEquals("ketch $expected\n", out.stdout)
        assertTrue(" · preview" in out.stdout, "--version must mark the channel as preview: ${out.stdout}")
        if (VERSION != "0.1.0") assertNotEquals("ketch 0.1.0\n", out.stdout, "version must not be hard-coded")
    }

    @Test
    fun selfVersionMarksPreview() {
        val out = ketchBare("self", "version")
        assertTrue(out.success, "$out")
        assertEquals("ketch $expected", out.stdout.lines().first())
    }

    @Test
    fun shortVersionFlagMarksPreview() {
        val out = ketchBare("-V")
        assertTrue(out.success, "$out")
        assertEquals("", out.stderr)
        assertEquals("ketch $expected\n", out.stdout)
    }

    @Test
    fun doctorVersionLineMarksPreview() {
        val out = ketchBare("--root", temp.path.toString(), "doctor")
        val line = assertNotNull(out.stdout.lines().find { "version" in it }, "doctor prints a version check, got:\n${out.stdout}")
        assertTrue("ketch $expected" in line, "doctor version must mark preview: $line")
    }

    @Test
    fun doctorJsonVersionMarksPreview() {
        val out = ketchBare("--root", temp.path.toString(), "doctor", "--json")
        val checks = Json.parseToJsonElement(out.stdout).jsonObject["checks"]!!.jsonArray
        val version = assertNotNull(checks.find { it.jsonObject["name"]?.jsonPrimitive?.content == "version" }, "doctor json has a version check")
        val detail = version.jsonObject["detail"]!!.jsonPrimitive.content
        assertTrue(detail.startsWith("ketch $expected"), "doctor json version must mark preview: $detail")
    }

    @Test
    fun helpDescribesTheProductAndInstallCommand() {
        val out = ketchBare("--help")
        assertTrue(out.success, "$out")
        assertTrue("installs command-line tools" in out.stdout && "install" in out.stdout, out.stdout)
        assertEquals("", out.stderr)
    }

    @Test
    fun anUnknownCommandExitsNonzeroAndExplainsTheProblem() {
        val out = ketchBare("not-a-command")
        assertFalse(out.success)
        assertTrue("unrecognized subcommand" in out.stderr, out.stderr)
    }

    @Test
    fun anEmptyRootIsReportedWithoutTouchingTheCallersHome() {
        val root = temp.path.resolve("ketch-root")
        val out = ketchBare("--root", root.toString(), "list", "local", envs = mapOf("NO_COLOR" to "1"))
        assertTrue(out.success, "$out")
        assertEquals("nothing installed\n", out.stdout)
        assertEquals("", out.stderr)
        assertTrue(isDir(root))
    }

    @Test
    fun emptyHistoryAndStatsJsonDoNotCreateADatabase() {
        val root = temp.path.resolve("ketch-root").toString()
        val history = ketchBare("--root", root, "history", "--json", envs = mapOf("NO_COLOR" to "1"))
        assertTrue(history.success, "$history")
        assertEquals("", history.stderr)
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(history.stdout))

        val stats = ketchBare("--root", root, "stats", "--json", envs = mapOf("NO_COLOR" to "1"))
        assertTrue(stats.success, "$stats")
        assertEquals("", stats.stderr)
        val want =
            buildJsonObject {
                put("events", 0)
                put("installs", 0)
                put("upgrades", 0)
                put("uninstalls", 0)
                put("packages", 0)
                put("mean_duration_ms", JsonNull)
                put("first_at", JsonNull)
                put("last_at", JsonNull)
            }
        assertEquals(want, Json.parseToJsonElement(stats.stdout))
        assertFalse(SystemFileSystem.exists(temp.path.resolve("ketch-root/stats.db")))
    }

    @Test
    fun packageHistoryNamesThePackageWhenNoEventsMatch() {
        val out = ketchBare("--root", temp.path.resolve("root").toString(), "history", "ripgrep", envs = mapOf("NO_COLOR" to "1"))
        assertTrue(out.success, "$out")
        assertEquals("no history recorded for ripgrep\n", out.stdout)
        assertEquals("", out.stderr)
    }

    @Test
    fun historyRejectsANegativeLimit() {
        val out = ketchBare("history", "--limit", "-1")
        assertFalse(out.success)
        assertTrue("unexpected argument '-1'" in out.stderr, out.stderr)
    }
}
