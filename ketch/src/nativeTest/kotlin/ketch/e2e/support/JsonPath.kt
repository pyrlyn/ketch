// Reading a command's JSON output the way the Rust tests index `serde_json::Value`: a missing key is null, not a throw.
package ketch.e2e.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun json(text: String): JsonElement = Json.parseToJsonElement(text)

operator fun JsonElement.get(key: String): JsonElement = (this as? JsonObject)?.get(key) ?: JsonNull

operator fun JsonElement.get(index: Int): JsonElement = (this as? JsonArray)?.getOrNull(index) ?: JsonNull

/** The string this is, or null for anything else (Rust `Value::as_str`). */
val JsonElement.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The elements of this array; fails the test when it is not one. */
val JsonElement.items: List<JsonElement> get() = this as? JsonArray ?: error("not a JSON array: $this")
