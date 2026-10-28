package ketch.core.config

import fs.joinPath
import fs.readText
import ketch.core.testing.RUST_FIXTURES
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigSchemaTest {
    private val text = readText(joinPath(RUST_FIXTURES, "config.schema.json"))

    @Test
    fun theCommittedSchemaDescribesEveryConfigKey() {
        // Rust regenerates the schema from its types; the port checks its own types have not drifted from it.
        val described =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("properties")
                .jsonObject.keys
        val descriptor = serializer<ConfigFile>().descriptor
        assertEquals(described, (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet())
    }

    @Test
    fun theSchemaListsTheLogValues() {
        for (value in listOf("off", "error", "warn", "info", "debug", "text", "json")) {
            assertTrue("\"$value\"" in text, value)
        }
    }
}
