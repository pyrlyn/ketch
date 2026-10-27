package ketch.core.testing

import fs.readText
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement

/** The schemas and docs copied from Rust ketch, which the port must keep accepting and refusing alike. */
val RUST_FIXTURES = Path("fixtures/rust")

/** A compiled JSON Schema; the validator library stays behind this, so it can be replaced alone. */
class Schema private constructor(
    private val schema: JsonSchema,
) {
    /** What [instance] breaks, one line per error; empty when it is valid. */
    fun errors(instance: JsonElement): List<String> {
        val out = ArrayList<String>()
        schema.validate(instance) { e: ValidationError -> out += "${e.objectPath}: ${e.message}" }
        return out
    }

    companion object {
        /** A committed schema, as an editor would load it. */
        fun file(name: String) = Schema(JsonSchema.fromDefinition(readText(Path(RUST_FIXTURES, name))))

        /**
         * A schema `pattern` as an editor runs it: ECMA-262, where `$` is the end of the text. The validator compiles
         * patterns as Kotlin regexes, whose `$` also matches before a final line break, so a name ending in `\n`
         * would pass there and not in the editor; `\z` is ECMA's `$`.
         */
        fun ecmaPattern(pattern: String): Regex = Regex(pattern.replace(UNESCAPED_DOLLAR, Regex.escapeReplacement("\\z")))

        private val UNESCAPED_DOLLAR = Regex("(?<!\\\\)\\$")
    }
}
