package ketch.core

import db.room.RoomDbDriver
import ketch.core.hash.Sha256
import kotlinx.serialization.json.JsonPrimitive
import semver.VersionKey
import toml.ktoml.KtomlReader
import tomlaot.TomlDoc
import yamlaot.Yaml
import yamlaot.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The composite build resolves each kotlin-kit library ketch uses, and the SHA-256 wrapper, on this target.
class SharedLibsTest {
    @Test
    fun tomlReadsAndTheEditorKeepsComments() {
        assertEquals(JsonPrimitive(1), KtomlReader.parse("a = 1\n")["a"])
        val text = "# keep\n[tool]\nname = \"jq\"\n"
        assertEquals(text, TomlDoc.parse(text).toString())
    }

    @Test
    fun yamlReadsAMapping() {
        val doc = Yaml.parse("PackageIdentifier: jqlang.jq\n") as YamlValue.Map
        assertEquals(YamlValue.Str("jqlang.jq"), doc["PackageIdentifier"])
    }

    @Test
    fun semverOrdersPrereleasesFirst() {
        assertTrue(VersionKey.parse("1.0.0-rc.1")!! < VersionKey.parse("1.0.0")!!)
    }

    @Test
    fun sha256StreamsInChunks() {
        val abc = "abc".encodeToByteArray()
        val hasher = Sha256()
        abc.indices.forEach { hasher.update(abc, it, 1) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hasher.hex())
        assertEquals(Sha256.hex(abc), Sha256().update(abc).hex())
    }

    @Test
    fun bundledSqliteAnswers() {
        assertTrue(RoomDbDriver.version().first().isDigit())
    }
}
