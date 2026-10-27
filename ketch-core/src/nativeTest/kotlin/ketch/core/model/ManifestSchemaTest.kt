package ketch.core.model

import fs.readText
import ketch.core.error.KetchError
import ketch.core.manifest.BUILTIN_TOML
import ketch.core.testing.RUST_FIXTURES
import ketch.core.testing.Schema
import ketch.core.tomlfile.parse
import ketch.core.tomlfile.toJson
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The committed `manifest.schema.json` is generated from the Rust types, so here it is the fixed point: what an
 * editor passes, the port must take, and what the port refuses, the schema must refuse too.
 */
class ManifestSchemaTest {
    private val schema = Schema.file("manifest.schema.json")

    private fun errors(tomlText: String) = schema.errors(toJson(tomlText, "manifest"))

    /** Whether ketch itself takes this manifest: the types, then `validate`. */
    private fun ketchAccepts(tomlText: String): Boolean =
        try {
            parse<Manifest>(tomlText, "manifest").validate()
            true
        } catch (_: KetchError) {
            false
        }

    @Test
    fun theSchemaDescribesEveryManifestKey() {
        // Rust regenerates the schema from its types; the port checks its own types have not drifted from it.
        val described = schemaJson().getValue("properties").jsonObject.keys
        val descriptor = serializer<Manifest>().descriptor
        assertEquals(described, (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet())
    }

    @Test
    fun everyManifestKetchShipsValidatesAgainstTheSchema() {
        val own = errors(readText(Path(RUST_FIXTURES, "ketch.toml")))
        assertTrue(own.isEmpty(), "ketch.toml: $own")
        val packages = toJson(BUILTIN_TOML, "builtin.toml").getValue("package").jsonArray
        assertTrue(packages.isNotEmpty())
        for (pkg in packages) {
            val found = schema.errors(pkg)
            assertTrue(found.isEmpty(), "builtin ${pkg.jsonObject["name"]}: $found")
        }
    }

    /**
     * Every TOML example in `MANIFESTS.md`, and the ones in `REGISTRY.md` that open with a `# <folder>/ketch.toml`
     * comment. Most show one table or key, so a `source` is added where the example leaves it out; each must also be
     * one ketch accepts, or the example is wrong rather than the schema.
     */
    @Test
    fun everyManifestExampleInTheDocsValidatesAgainstTheSchema() {
        var seen = 0
        for (file in listOf("MANIFESTS.md", "REGISTRY.md")) {
            // A Windows checkout may have turned LF into CRLF.
            val text = readText(Path(RUST_FIXTURES, file)).replace("\r\n", "\n")
            for (block in text.split("```toml\n").drop(1)) {
                val body = block.substringBefore("```")
                val firstLine = body.lineSequence().first()
                val namesAPackageFile = firstLine.startsWith('#') && firstLine.endsWith("/ketch.toml")
                if (file != "MANIFESTS.md" && !namesAPackageFile) continue
                val parsed = toJson(body, file)
                var example = body
                if ("source" !in parsed) example = "source = \"github:o/r\"\n$example"
                if ("name" !in parsed) example = "name = \"example\"\n$example"
                assertTrue(ketchAccepts(example), "$file: ketch refuses\n$example")
                val found = errors(example)
                assertTrue(found.isEmpty(), "$file: $found\n$example")
                seen += 1
            }
        }
        assertTrue(seen >= MIN_DOC_EXAMPLES, "found only $seen examples; did the docs move?")
    }

    /** Manifests ketch refuses, each for a reason the schema can state; an editor must not pass a file that fails at install. */
    @Test
    fun theSchemaRefusesWhatKetchRefuses() {
        val cases =
            listOf(
                "source = \"o/r\"\nsourse = \"x\"",
                "source = \"ripgrep\"",
                "source = \"github:\"",
                "source = \"o/r\"\nkind = \"library\"",
                "name = \"../evil\"\nsource = \"o/r\"",
                "name = \"-tool\"\nsource = \"o/r\"",
                "source = \"o/r\"\nbin = [{}]",
                "source = \"o/r\"\nbin = [{ name = \"a/b\" }]",
                "source = \"o/r\"\nstrip_prefix = 9",
                "source = \"o/r\"\nstrip_prefix = -1",
                "source = \"o/r\"\nprovides = [\"r g\"]",
                "source = \"o/r\"\nprovides = [\"\"]",
                "source = \"o/r\"\nextra_paths = [{ path = \"x.1\", kind = \"doc\" }]",
                "source = \"o/r\"\nextra_paths = [{ path = \"x.1\", kind = \"man\", sect = \"1\" }]",
                "source = \"o/r\"\n[trust]\nverifier = \"cosign\"",
                "source = \"o/r\"\n[trust]\nverifier = \"minisign\"\nmode = \"ignore\"",
                "source = \"o/r\"\n[trust]\nverifier = \"minisign\"\nkey = \"x\"",
                "source = \"o/r\"\n[hooks]\nafter_instal = \"x\"",
                "source = \"o/r\"\n[hooks]\nafter_install = \" \"",
            )
        for (case in cases) {
            val withName = if (case.startsWith("name")) case else "name = \"tool\"\n$case"
            assertFalse(ketchAccepts(withName), "ketch takes\n$withName")
            assertTrue(errors(case).isNotEmpty(), "the schema takes\n$case")
        }
    }

    /**
     * The schema's patterns say what ketch's checks say, for the characters where they could disagree: Unicode
     * whitespace (which ECMA-262 and Rust draw differently) and what `sanitizeComponent` and `PackageRef.parse`
     * single out. Rust draws strings from proptest; this is every string of up to two of those characters.
     */
    @Test
    fun schemaPatternsAgreeWithTheChecks() {
        val json = schemaJson()
        val defs = json.getValue("\$defs").jsonObject
        val props = json.getValue("properties").jsonObject

        fun JsonObject.pattern() = getValue("pattern").jsonPrimitive.content
        val patterns =
            listOf(
                defs.getValue("PackageRef").jsonObject.pattern(),
                props.getValue("name").jsonObject.pattern(),
                props
                    .getValue("provides")
                    .jsonObject
                    .getValue("items")
                    .jsonObject
                    .pattern(),
                defs
                    .getValue("Hooks")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("after_install")
                    .jsonObject
                    .pattern(),
            ).map(Schema::ecmaPattern)
        val base = Manifest.inferred(PackageRef.github("o/r"))
        for (text in sample()) {
            val port =
                listOf(
                    PackageRef.parse(text) != null,
                    valid(base.copy(name = text)),
                    valid(base.copy(provides = listOf(text))),
                    valid(base.copy(hooks = Hooks(afterInstall = text))),
                )
            patterns.zip(port).forEachIndexed { which, (pattern, expected) ->
                assertEquals(expected, pattern.containsMatchIn(text), "pattern $which on ${text.map { it.code }}")
            }
        }
    }

    private fun valid(manifest: Manifest): Boolean =
        try {
            manifest.validate()
            true
        } catch (_: KetchError) {
            false
        }

    private fun sample(): List<String> {
        val one = TRICKY.map(Char::toString) + (Char(0xD83D).toString() + Char(0xDE00))
        return listOf("") + one + one.flatMap { a -> one.map { b -> a + b } } + listOf("o/r", "github:o/r", "a b", "-ab", "ab.", "x:y ")
    }

    private fun schemaJson(): JsonObject =
        kotlinx.serialization.json.Json
            .parseToJsonElement(readText(Path(RUST_FIXTURES, "manifest.schema.json")))
            .jsonObject

    private companion object {
        /** The docs held this many manifest examples when the schema was copied. */
        const val MIN_DOC_EXAMPLES = 8

        /** Code points, so the invisible ones are visible here. */
        val TRICKY =
            listOf(
                0x20,
                0x09,
                0x0a,
                0x0d,
                0x0b,
                0x85,
                0xa0,
                0xfeff,
                0x2028,
                0x3000,
                0x180e,
                0x2f,
                0x5c,
                0x3a,
                0x2e,
                0x2d,
                0x5f,
                0x00,
                0x1f,
                0x7f,
                0x9f,
                0x202e,
                0x2066,
                0x200b,
                0x61,
                0x5a,
                0x30,
                0xe9,
            ).map(::Char)
    }
}
