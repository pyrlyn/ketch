// Every TOML file ketch owns is read, written and described here (Rust `toml_file`). `config.toml`, the registry's
// metadata, package files and `ketch.lock` each have Kotlin types; this file turns text into those types and back.
// Keeping the TOML libraries behind one file means one answer to how a parse error names its file and how a file is
// rendered, and one place to look when the format or a library changes.
package ketch.core.tomlfile

import ketch.core.error.KetchError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import toml.TomlException
import toml.TomlParser
import toml.TomlWriter
import tomlaot.Shape
import tomlaot.TomlAotException
import tomlaot.TomlDoc
import tomlaot.TomlTable
import tomlaot.TomlValue

/**
 * How a TOML tree maps onto the Kotlin types. A null is an absent key, as TOML has no null and Rust skips a `None`;
 * a default is written out, as serde writes every field it is not told to skip.
 */
private val TOML_JSON =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/** Parse [text] into a [T]. [what] names the file in the error, so a user can find the line it complains about. */
internal fun <T> parse(
    text: String,
    what: String,
    deserializer: DeserializationStrategy<T>,
): T = decode(tree(text, what), what, deserializer)

internal inline fun <reified T> parse(
    text: String,
    what: String,
): T = parse(text, what, serializer<T>())

/** Render [value] as a TOML document laid out as Rust `toml::to_string_pretty` lays it out. */
internal fun <T> render(
    value: T,
    what: String,
    serializer: SerializationStrategy<T>,
): String {
    val tree =
        try {
            TOML_JSON.encodeToJsonElement(serializer, value)
        } catch (e: SerializationException) {
            throw KetchError.Parse(what, e.message.orEmpty(), e)
        }
    return TomlWriter.document(tree as? JsonObject ?: throw KetchError.Parse(what, "a TOML document must be a table"))
}

internal inline fun <reified T> render(
    value: T,
    what: String,
): String = render(value, what, serializer<T>())

/** Parse [text] as TOML and return it as a JSON tree, for validating a file against a JSON Schema. */
internal fun toJson(
    text: String,
    what: String,
): JsonObject = tree(text, what)

/** A quoted, escaped TOML string, written by the TOML writer so escaping is never hand-rolled. */
internal fun stringLiteral(text: String): String = TomlWriter.string(text)

/** A TOML array of strings, escaped the same way [stringLiteral] escapes. */
internal fun stringArrayLiteral(items: List<String>): String = TomlWriter.value(JsonArray(items.map(::JsonPrimitive)))

private fun tree(
    text: String,
    what: String,
): JsonObject =
    try {
        TomlParser.parse(text)
    } catch (e: TomlException) {
        throw KetchError.Parse(what, e.message.orEmpty(), e)
    }

private fun <T> decode(
    tree: JsonObject,
    what: String,
    deserializer: DeserializationStrategy<T>,
): T =
    try {
        TOML_JSON.decodeFromJsonElement(deserializer, tree)
    } catch (e: SerializationException) {
        throw KetchError.Parse(what, e.message.orEmpty(), e)
    } catch (e: IllegalArgumentException) {
        // A type's own `init` check refusing a value is the file's fault too.
        throw KetchError.Parse(what, e.message.orEmpty(), e)
    }

/**
 * A parsed TOML document whose keys a caller reads, or fills in, before it becomes a typed value. A registry
 * folder's `ketch.toml` may leave `name` out, because the folder supplies it; the type cannot see the folder, so the
 * key is added to the document first and the result decoded as if the file had said it.
 */
internal class Document private constructor(
    private var table: JsonObject,
    private val what: String,
) {
    /** The top-level [key], when it is a string. */
    fun str(key: String): String? = (table[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Set the top-level [key] to the string [value]. */
    fun setStr(
        key: String,
        value: String,
    ) {
        table = JsonObject(table + (key to JsonPrimitive(value)))
    }

    /** Whether the top-level [key] holds an array. */
    fun isArray(key: String): Boolean = table[key] is JsonArray

    /** Decode the document, keys added or not, into a [T]. */
    fun <T> deserialize(deserializer: DeserializationStrategy<T>): T = decode(table, what, deserializer)

    inline fun <reified T> deserialize(): T = deserialize(serializer<T>())

    companion object {
        /** Parse [text]; [what] names the file in this and every later error. */
        fun parse(
            text: String,
            what: String,
        ) = Document(tree(text, what), what)
    }
}

/**
 * A TOML file a key is inserted into while everything else (comments, key order, spacing) renders back exactly as
 * it was read. The file is the user's, so a round trip through the types, which keep only the values, would be a
 * rewrite of what they wrote.
 */
internal class EditDocument private constructor(
    private val doc: TomlDoc,
) {
    /**
     * The table describing one package: the whole document when it is a single manifest, or else the `[[package]]`
     * entry whose `name` [isPackage] accepts. Null when no entry does.
     */
    fun packageTable(isPackage: (String) -> Boolean): EditTable? {
        if (doc.shape(PACKAGE) != Shape.ArrayOfTables) return EditTable(doc.root)
        return doc.arrayOfTables(PACKAGE).firstOrNull { t -> t.str("name")?.let(isPackage) == true }?.let(::EditTable)
    }

    /** The document as text, edits included. */
    fun render(): String = doc.toString()

    companion object {
        private const val PACKAGE = "package"

        /** Parse [text]; [what] names the file in the error. */
        fun parse(
            text: String,
            what: String,
        ): EditDocument {
            // The editor reads lines, not the grammar; the real reader refuses what is not TOML first.
            tree(text, what)
            return try {
                EditDocument(TomlDoc.parse(text))
            } catch (e: TomlAotException) {
                throw KetchError.Parse(what, e.message.orEmpty(), e)
            }
        }
    }
}

/** One table of an [EditDocument], borrowed for an edit. */
internal class EditTable(
    private val table: TomlTable,
) {
    /** Whether the table already has [key], whatever its value. */
    fun containsKey(key: String): Boolean = table.containsKey(key)

    /** Set [key] to `[{ <field> = "<value>" }, …]`, one inline table per value, in order. */
    fun setInlineTables(
        key: String,
        field: String,
        values: List<String>,
    ) {
        val list = JsonArray(values.map { JsonObject(mapOf(field to JsonPrimitive(it))) })
        table[key] = TomlValue.Raw(TomlWriter.value(list))
    }
}
