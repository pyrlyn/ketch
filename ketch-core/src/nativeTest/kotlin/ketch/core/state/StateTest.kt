package ketch.core.state

import fs.atomic
import fs.readText
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.LinkRole
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.PackageSpec
import ketch.core.model.Provenance
import ketch.core.model.TargetSpec
import ketch.core.model.TrustResult
import ketch.core.model.Verifier
import ketch.core.model.Version
import ketch.core.model.host
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateTest {
    private fun pkg(name: String) =
        InstalledPackage(
            name = name,
            version = Version.parse("1.0.0"),
            source = PackageRef.github("o/r"),
            tag = "v1.0.0",
            target = TargetSpec.host(),
            assetName = "a.tar.gz",
            sha256 = "0".repeat(64),
            checksumVerified = true,
            installedAt = 0,
            prefix = Path("/tmp/x"),
            origin = ManifestOrigin.Inferred,
        )

    private fun write(
        path: Path,
        text: String,
    ) = atomic(path, text.encodeToByteArray())

    private fun count(
        text: String,
        needle: String,
    ) = Regex(Regex.escape(needle)).findAll(text).count()

    /** A version 1 file from before retention, trust, provenance and the rest existed; [extra] goes inside the package. */
    private fun v1(extra: String = "") =
        """
        {
            "version": 1,
            "packages": {
                "ripgrep": {
                    "name": "ripgrep",
                    "version": "14.1.0",
                    "source": "github:BurntSushi/ripgrep",
                    "tag": "14.1.0",
                    "target": {"os": "macos", "arch": "aarch64"},
                    "asset_name": "a.tar.gz",
                    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                    "installed_at": 0,
                    "prefix": "/tmp/x",
                    "origin": "inferred"$extra
                }
            }
        }
        """.trimIndent()

    @Test
    fun aVersionedSpecFindsThePackageByNameOrSource() {
        val state = State().apply { insert(pkg("tool")) }
        for (raw in listOf("tool", "tool@2.0.0", "o/r@2.0.0", "github:o/r")) {
            assertEquals("tool", state.findSpec(PackageSpec.parse(raw))?.name, raw)
        }
        assertNull(state.findSpec(PackageSpec.parse("other")))
    }

    @Test
    fun anEmptyStateFileIsNotAnEmptyInstall(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            write(path, "")
            assertFailsWith<Exception>("corruption must be loud") { State.loadPath(path) }
            fs.removeFile(path)
            assertTrue(State.loadPath(path).packages.isEmpty())
        }

    @Test
    fun roundTripsThroughDisk(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            State().apply { insert(pkg("ripgrep")) }.savePath(path)
            val loaded = State.loadPath(path)
            assertEquals(STATE_VERSION, loaded.version)
            assertEquals(pkg("ripgrep"), loaded.get("ripgrep"))
        }

    @Test
    fun missingFileIsAnEmptyState(): Unit =
        withTempDir { dir ->
            assertTrue(State.loadPath(Path(dir, "nope.json")).packages.isEmpty())
        }

    @Test
    fun refusesANewerStateVersion(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            write(path, """{"version":99,"packages":{}}""")
            assertFailsWith<Exception> { State.loadPath(path) }
        }

    @Test
    fun findsBySourceIdAndBinary() {
        val state = State().apply { insert(pkg("ripgrep")) }
        assertNotNull(state.find("o/r"))
        assertNotNull(state.find("github:o/r"))
        assertNull(state.find("absent"))
    }

    @Test
    fun aV1StateWithoutRetentionFieldsStillLoads(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            write(path, v1())
            val loaded = State.loadPath(path)
            assertEquals(STATE_VERSION, loaded.version)
            assertEquals(1u, loaded.retention.keep)
            val pkg = assertNotNull(loaded.get("ripgrep"))
            assertTrue(pkg.retained.isEmpty())
            assertEquals(TrustResult.NotApplicable, pkg.trust)
            assertNull(pkg.provenance)
            assertNull(pkg.binChoice)
            assertEquals(Channel.Stable, pkg.channel)
            assertEquals("first use", pkg.publisherTrust())
            assertEquals("14.1.0", pkg.version.toString())
        }

    @Test
    fun aKeyANewerKetchAddedIsSkippedAsRustSkipsIt(): Unit =
        withTempDir { dir ->
            // InstalledPackage does not deny unknown fields in Rust, so a field from a newer ketch must not lose the
            // whole record here.
            val path = Path(dir, "state.json")
            write(path, v1(", \"from_the_future\": true"))
            assertNotNull(State.loadPath(path).get("ripgrep"))
        }

    @Test
    fun provenanceRoundTripsAndStaysOutOfUnsignedRecords(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            val signed = Provenance(Verifier.Minisign, "minisign key RWQ", "a.tar.gz.minisig", "1".repeat(64), signed = "SHA256SUMS")
            val state = State()
            state.insert(pkg("signed").copy(provenance = signed))
            state.insert(pkg("plain"))
            state.savePath(path)
            val written = readText(path)
            assertEquals(1, count(written, "\"provenance\""), written)
            val loaded = State.loadPath(path)
            assertEquals(signed, loaded.get("signed")?.provenance)
            assertEquals("signed", loaded.get("signed")?.publisherTrust())
            assertNull(loaded.get("plain")?.provenance)
        }

    @Test
    fun aBinChoiceRoundTripsAndStaysOutOfRecordsWithoutOne(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            val state = State()
            state.insert(pkg("chosen").copy(binChoice = "rtok-cli"))
            state.insert(pkg("plain"))
            state.savePath(path)
            val written = readText(path)
            assertEquals(1, count(written, "\"bin_choice\""), written)
            val loaded = State.loadPath(path)
            assertEquals("rtok-cli", loaded.get("chosen")?.binChoice)
            assertNull(loaded.get("plain")?.binChoice)
        }

    @Test
    fun aV1LinkWithoutARoleStillReadsAsABinary(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            write(path, v1(""", "links": [{"link": "/tmp/bin/rg", "target": "/tmp/x/rg", "kind": "symlink"}]"""))
            val pkg = assertNotNull(State.loadPath(path).get("ripgrep"))
            assertEquals(1, pkg.links.size)
            assertEquals(LinkRole.Binary, pkg.links[0].role)
            assertEquals(1, pkg.binaries().size)
        }

    @Test
    fun aPreChannelRoundTripsAndStableStaysOutOfTheFile(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "state.json")
            val state = State()
            state.insert(pkg("beta").copy(channel = Channel.Pre))
            state.insert(pkg("plain"))
            state.savePath(path)
            val written = readText(path)
            assertEquals(1, count(written, "\"channel\""), written)
            val loaded = State.loadPath(path)
            assertEquals(Channel.Pre, loaded.get("beta")?.channel)
            assertEquals(Channel.Stable, loaded.get("plain")?.channel)
        }

    @Test
    fun theFileIsLaidOutAsSerdeJsonWritesIt(): Unit =
        withTempDir { dir ->
            // Two-space indent, `"key": value`, packages in name order and a trailing newline, as Rust ketch writes it.
            val path = Path(dir, "state.json")
            val state = State()
            state.insert(pkg("zeta"))
            state.insert(pkg("alpha"))
            state.savePath(path)
            val written = readText(path)
            assertTrue(written.startsWith("{\n  \"version\": 1,\n  \"retention\": {\n    \"keep\": 1\n  },\n"), written)
            assertTrue(written.indexOf("\"alpha\"") < written.indexOf("\"zeta\""), written)
            assertTrue("\"manifest\": null" in written, "an absent manifest is written as null, as serde writes None")
            assertTrue(written.endsWith("}\n"), written)
        }
}
