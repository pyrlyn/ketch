// JSON output of the commands, written as serde_json's `to_string_pretty` writes it.
package ketch.cmd

import ketch.ui.out
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal val PRETTY =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/**
 * An object built the way `serde_json::json!` builds one. Without serde_json's `preserve_order`, which ketch does not
 * enable, its map is a `BTreeMap`, so the keys come out sorted rather than in the order they were written.
 */
internal fun jsonOf(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(entries.sortedBy { it.first }.toMap())

/** Print [value] on stdout, pretty. */
internal fun printJson(value: JsonElement) = out(PRETTY.encodeToString(JsonElement.serializer(), value))
