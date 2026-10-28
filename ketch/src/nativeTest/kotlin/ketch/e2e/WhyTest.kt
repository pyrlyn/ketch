// Binary-level `ketch why` tests (Rust tests/why.rs), against the offline test plugin so the explanation is the
// production resolver, not a reconstructed one. Snapshots are Rust's tests/snapshots/why, kept as they are.
@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package ketch.e2e

import fs.bytesIfExists
import fs.canon
import ketch.cmd.PRETTY
import ketch.cmd.sortedJson
import ketch.e2e.support.Entry
import ketch.e2e.support.Release
import ketch.e2e.support.Sandbox
import ketch.e2e.support.get
import ketch.e2e.support.hostArch
import ketch.e2e.support.json
import ketch.e2e.support.ketch
import ketch.e2e.support.nativeName
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WhyTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun toolArchive(version: String) = tarGz(Entry.program("whypkg-$version/bin/whypkg", "whypkg $version"))

    private fun foreignName(
        pkg: String,
        version: String,
    ) = if (Platform.osFamily == OsFamily.MACOSX) {
        "$pkg-$version-${hostArch()}-unknown-linux-gnu.tar.gz"
    } else {
        "$pkg-$version-${hostArch()}-apple-darwin.tar.gz"
    }

    private fun hostTarget() = "${if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux"}-${hostArch()}"

    private fun publishPair(
        id: String,
        version: String,
    ) {
        val native = sandbox.asset(nativeName(id, version), toolArchive(version))
        val foreign = sandbox.asset(foreignName(id, version), toolArchive("foreign"))
        sandbox.publish(id, listOf(Release(version, listOf(foreign, native))))
    }

    private fun userManifest(
        name: String,
        body: String,
    ) = writeText(sandbox.root.resolve("manifests/$name.toml"), body)

    private fun redact(text: String): String {
        var out = redactRootPaths(text).replace(hostTarget(), "{target}")
        // Asset names embed arch and OS triple; replace them before `{arch}` so one snapshot set fits every host.
        for (pkg in listOf("whypkg", "ripgrep")) {
            for (version in listOf("2.0.0-rc.1", "1.0.0")) {
                out = out.replace(nativeName(pkg, version), "{$pkg-native-$version}").replace(foreignName(pkg, version), "{$pkg-foreign-$version}")
            }
        }
        out = out.replace(hostArch(), "{arch}")
        for (reason in listOf("darwin / {arch} / tar.gz", "linux / gnu / {arch} / tar.gz", "windows / {arch} / zip")) {
            out = out.replace(reason, "{native-reason}")
        }
        return normalizeWhyText(out)
    }

    /** Wipe the absolute sandbox root, as printed and as resolved, then any path through it to the manifests. */
    private fun redactRootPaths(text: String): String {
        var out = text
        for (form in listOf(sandbox.root.toString(), canon(sandbox.root).toString())) out = out.replace(form, "{root}")
        out = scrubThroughRootSuffix(out, "/manifests/")
        return scrubThroughRootSuffix(out, "/registry/")
    }

    private fun redactJson(value: JsonElement): JsonElement =
        when (value) {
            is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
            is JsonArray -> JsonArray(value.map(::redactJson))
            is JsonObject -> JsonObject(value.mapValues { redactJson(it.value) })
        }

    /** Parse before redacting, then print as serde_json's `to_string_pretty` prints a `Value`: keys sorted. */
    private fun snapshotJson(
        name: String,
        text: String,
    ) = assertSnapshot("$name.json", PRETTY.encodeToString(JsonElement.serializer(), sortedJson(normalizeWhyJson(redactJson(json(text))))) + "\n")

    private fun snapshotText(
        name: String,
        text: String,
    ) = assertSnapshot("$name.txt", redact(text))

    private fun assertSnapshot(
        name: String,
        body: String,
    ) {
        val path = Path(checkNotNull(getenv("SNAPSHOT_DIR")?.toKString()) { "SNAPSHOT_DIR is not set" }, "why/$name")
        val expected = bytesIfExists(path)?.decodeToString()
        when {
            expected == body -> {}

            getenv("UPDATE_SNAPSHOTS") != null -> {
                writeText(path, body)
            }

            expected == null -> {
                fail("missing snapshot $path; run with UPDATE_SNAPSHOTS=1")
            }

            else -> {
                assertEquals(expected, body, "snapshot $name drifted")
            }
        }
    }

    @Test
    fun whyJsonAndTextForAUserManifestAlias() {
        publishPair("whypkg", "1.0.0")
        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\nprovides = [\"whyalias\"]\n")

        val out = sandbox.ok("why", "whyalias", "--json")
        val parsed = json(out)
        assertEquals("user", parsed["manifest"]["tier"].str)
        assertEquals("whypkg", parsed["manifest"]["name"].str)
        assertEquals("whyalias", parsed["manifest"]["matched"].str)
        assertEquals(nativeName("whypkg", "1.0.0"), parsed["candidate"]["name"].str)
        assertTrue((parsed["assets"]["rejected"] as? JsonArray)?.isNotEmpty() == true, "foreign asset should be rejected: $out")
        snapshotJson("alias", out)

        val text = sandbox.ok("why", "whyalias")
        assertTrue("user" in text && "whyalias" in text, text)
        snapshotText("alias", text)
    }

    @Test
    fun whyPrefersAUserManifestOverTheRegistry() {
        publishPair("whypkg", "1.0.0")
        sandbox.registryPackage("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\n")
        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\n")
        val out = sandbox.ok("why", "whypkg", "--json")
        assertEquals("user", json(out)["manifest"]["tier"].str)
        snapshotJson("user-over-registry", out)
        snapshotText("user-over-registry", sandbox.ok("why", "whypkg"))
    }

    @Test
    fun whyPrefersTheRegistryOverBuiltin() {
        publishPair("ripgrep", "1.0.0")
        sandbox.registryPackage("ripgrep", "name = \"ripgrep\"\nsource = \"test:ripgrep\"\nprovides = [\"rg\"]\n")
        val out = sandbox.ok("why", "rg", "--json")
        val parsed = json(out)
        assertEquals("registry", parsed["manifest"]["tier"].str)
        assertEquals("test", parsed["source"]["scheme"].str)
        snapshotJson("registry-over-builtin", out)
        snapshotText("registry-over-builtin", sandbox.ok("why", "rg"))
    }

    @Test
    fun whySelectsStableOverAPrereleaseUnlessTheManifestAsks() {
        val stable = sandbox.asset(nativeName("whypkg", "1.0.0"), toolArchive("1.0.0"))
        val pre = sandbox.asset(nativeName("whypkg", "2.0.0-rc.1"), toolArchive("2.0.0-rc.1"))
        sandbox.publish("whypkg", listOf(Release("2.0.0-rc.1", listOf(pre)).intoPrerelease(), Release("1.0.0", listOf(stable))))
        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\n")

        val out = sandbox.ok("why", "whypkg", "--json")
        val parsed = json(out)
        assertEquals("1.0.0", parsed["version"]["selected"]["version"].str)
        assertEquals(JsonPrimitive(false), parsed["version"]["include_prerelease"])
        snapshotJson("prerelease-excluded", out)
        snapshotText("prerelease-excluded", sandbox.ok("why", "whypkg"))

        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\nprerelease = true\n")
        val preOut = sandbox.ok("why", "whypkg", "--json")
        assertEquals("2.0.0-rc.1", json(preOut)["version"]["selected"]["version"].str)
        snapshotJson("prerelease-included", preOut)
        snapshotText("prerelease-included", sandbox.ok("why", "whypkg"))
    }

    @Test
    fun whyExplainsAPinnedAsset() {
        val pinned = sandbox.asset("whypkg-pinned.tar.gz", toolArchive("1.0.0"))
        val other = sandbox.asset(nativeName("whypkg", "1.0.0"), toolArchive("1.0.0"))
        sandbox.publish("whypkg", listOf(Release("1.0.0", listOf(other, pinned))))
        val targets = listOf("macos-aarch64", "macos-x86_64", "linux-x86_64", "linux-aarch64", "windows-x86_64", "windows-aarch64")
        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\n\n[asset.target]\n" + targets.joinToString("") { "\"$it\" = \"*-pinned.tar.gz\"\n" })

        val out = sandbox.ok("why", "whypkg", "--json")
        val parsed = json(out)
        assertEquals("whypkg-pinned.tar.gz", parsed["candidate"]["name"].str)
        assertTrue(parsed["candidate"]["reason"].str?.contains("pins") == true, out)
        snapshotJson("pinned-asset", out)
        snapshotText("pinned-asset", sandbox.ok("why", "whypkg"))
    }

    @Test
    fun whyReportsNoCompatibleAsset() {
        val foreign = sandbox.asset(foreignName("whypkg", "1.0.0"), toolArchive("1.0.0"))
        sandbox.publish("whypkg", listOf(Release("1.0.0", listOf(foreign))))
        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\n")

        val out = sandbox.ketch("why", "whypkg", "--json")
        assertFalse(out.success, "no compatible asset must fail")
        val parsed = json(out.stdout)
        assertEquals(JsonNull, parsed["candidate"], out.stdout)
        assertTrue((parsed["assets"]["rejected"] as? JsonArray)?.isNotEmpty() == true, out.stdout)
        snapshotJson("no-compatible-asset", out.stdout)
        val text = sandbox.ketch("why", "whypkg")
        assertFalse(text.success)
        snapshotText("no-compatible-asset", text.stdout)
    }

    @Test
    fun whyJsonKeepsSecretsAndBidiOut() {
        publishPair("whypkg", "1.0.0")
        userManifest("whypkg", "name = \"whypkg\"\nsource = \"test:whypkg\"\ndescription = \"safe‮evil\"\n")
        val out = sandbox.ok("why", "whypkg", "--json")
        assertFalse('‮' in out, out)
        assertFalse("SECRET" in out, out)
        assertFalse("authorization" in out, out)
        snapshotJson("no-secrets", out)
    }
}
