package ketch.core.tomlfile

import ketch.core.error.KetchError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TomlFileTest {
    @Serializable
    data class Sample(
        val name: String,
        val tags: List<String>,
    )

    private fun parsedString(doc: String): String? = toJson(doc, "sample")["v"]?.jsonPrimitive?.content

    @Test
    fun aParseErrorNamesTheFile() {
        val err = assertFailsWith<KetchError.Parse> { parse<Sample>("name = ", "/root/config.toml") }
        assertTrue("/root/config.toml" in err.message, err.message)
    }

    @Serializable
    data class Nested(
        val items: List<Sample> = emptyList(),
        val byName: Map<String, Sample> = emptyMap(),
    )

    @Test
    fun aRefusedValueIsNamedByItsKeyAndLine() {
        val list = assertFailsWith<KetchError.Parse> { parse<Nested>("[[items]]\nname = \"a\"\ntags = 1\n", "f.toml") }
        assertTrue("line 3, key `items[0].tags`" in list.message, list.message)
        val map = assertFailsWith<KetchError.Parse> { parse<Nested>("\n[byName.rg]\nname = 2\ntags = []\n", "f.toml") }
        assertTrue("line 3, key `byName.rg.name`" in map.message, map.message)
        assertFalse("at path" in map.message, map.message)
    }

    @Test
    fun renderedTextParsesBackToTheSameValue() {
        val sample = Sample("rg \"quoted\"", listOf("a", "b"))
        val text = render(sample, "sample")
        assertEquals(sample, parse<Sample>(text, "sample"))
    }

    @Test
    fun aKeySetOnADocumentIsDeserializedAsIfTheFileHadIt() {
        val doc = Document.parse("tags = [\"a\"]\n", "sample")
        assertNull(doc.str("name"))
        doc.setStr("name", "rg")
        assertEquals("rg", doc.str("name"))
        val sample = doc.deserialize<Sample>()
        assertEquals("rg", sample.name)
        assertEquals(listOf("a"), sample.tags)
    }

    @Test
    fun aKeyThatIsNotAStringReadsAsAbsent() {
        assertNull(Document.parse("name = 1\n", "sample").str("name"))
    }

    @Test
    fun aDocumentThatDoesNotFitTheTypeNamesTheFile() {
        val doc = Document.parse("name = \"rg\"\n", "/r/rg/ketch.toml")
        val err = assertFailsWith<KetchError.Parse> { doc.deserialize<Sample>() }
        assertTrue("/r/rg/ketch.toml" in err.message, err.message)
    }

    @Test
    fun aDocumentTellsAnArrayFromAnyOtherValue() {
        val doc = Document.parse("tags = [\"a\"]\nname = \"rg\"\n", "sample")
        assertTrue(doc.isArray("tags"))
        assertFalse(doc.isArray("name"))
        assertFalse(doc.isArray("missing"))
    }

    @Test
    fun anEditErrorNamesTheFile() {
        val err = assertFailsWith<KetchError.Parse>("unfinished key must not parse") { EditDocument.parse("name = ", "/root/rg.toml") }
        assertTrue("/root/rg.toml" in err.message, err.message)
    }

    @Test
    fun anInsertedKeyLeavesEveryOtherByteAsItWas() {
        val body =
            "# kept\n" +
                "[[package]]\n" +
                "source = \"github:o/a\"  # before name, on purpose\n" +
                "name = \"a\"\n" +
                "\n" +
                "[[package]]\n" +
                "name   = \"b\"\n" +
                "source = \"github:o/b\"\n"
        val doc = EditDocument.parse(body, "sample")
        val table = assertNotNull(doc.packageTable { it == "b" })
        assertFalse(table.containsKey("bin"))
        table.setInlineTables("bin", "name", listOf("x", "y"))
        assertTrue(table.containsKey("bin"))
        assertEquals("${body}bin = [{ name = \"x\" }, { name = \"y\" }]\n", doc.render())
    }

    @Test
    fun aSingleManifestIsItsOwnPackageTable() {
        assertNotNull(EditDocument.parse("name = \"a\"\n", "sample").packageTable { false })
    }

    @Test
    fun aPackageArrayWithoutTheNameHasNoPackageTable() {
        assertNull(EditDocument.parse("[[package]]\nname = \"a\"\n", "sample").packageTable { it == "b" })
    }

    @Test
    fun tomlTextBecomesTheSameJsonValue() {
        val json = toJson("name = \"rg\"\ntags = [\"a\", \"b\"]\n", "sample")
        assertEquals(JsonPrimitive("rg"), json["name"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), json["tags"])
        assertEquals(setOf("name", "tags"), json.keys)
    }

    @Test
    fun invalidTomlNamesTheFileWhenConvertedToJson() {
        val err = assertFailsWith<KetchError.Parse> { toJson("name = ", "/p/ketch.toml") }
        assertTrue("/p/ketch.toml" in err.message, err.message)
    }

    @Test
    fun aStringLiteralKeepsQuotesAndBackslashesRecoverable() {
        // The writer may choose a literal string over an escaped one; what matters is that the reader gets the same text back.
        for (text in listOf("say \"hi\"", "C:\\bin", "both \" and \\ and '")) {
            val doc = "v = ${stringLiteral(text)}"
            assertEquals(text, parsedString(doc), doc)
        }
        assertEquals("\"plain\"", stringLiteral("plain"))
    }

    @Test
    fun aStringLiteralEscapesControlBytesOnOneLine() {
        val rendered = stringLiteral("a\u0001b\u007fc\td")
        assertEquals("\"a\\u0001b\\u007Fc\\td\"", rendered)
        assertFalse('\n' in rendered)
    }

    @Test
    fun aStringLiteralParsesBackToTheSameText() {
        val text = "q\" b\\ n\n t\t \u0000 é"
        assertEquals(text, parsedString("v = ${stringLiteral(text)}"))
    }

    @Test
    fun aStringArrayLiteralRendersEachItemAndParsesBack() {
        assertEquals("[]", stringArrayLiteral(emptyList()))
        val rendered = stringArrayLiteral(listOf("a", "b\"c", "d\\e"))
        val back = toJson("v = $rendered", "sample")["v"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b\"c", "d\\e"), back)
    }
}
