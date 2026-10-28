// The brand subset of desktop/design/tokens.json, read the way the Rust tree's build.mjs reads it for the Windows and
// Linux apps: the same paths, the same appearance fallbacks, pixels as dp and sp.
package ketch.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Appearance(
    val isDark: Boolean,
    /** The variant key in the token's extensions, then where a token without it falls back to. */
    val keys: List<String>,
) {
    Light(false, emptyList()),
    Dark(true, listOf("dark")),
    HighContrast(false, listOf("highContrast")),
    HighContrastDark(true, listOf("highContrastDark", "dark")),
}

/** One step of the type scale; [lineHeight] is a multiple of [size], [size] and [letterSpacing] in pixels. */
data class TypeToken(
    val size: Float,
    val weight: Int,
    val lineHeight: Float,
    val letterSpacing: Float,
)

class Tokens private constructor(
    /** By path without the `color.` prefix (`accent.default`), in every appearance. */
    val colors: Map<String, Map<Appearance, Color>>,
    val space: Map<String, Dp>,
    val radius: Map<String, Dp>,
    val type: Map<String, TypeToken>,
) {
    fun color(
        path: String,
        appearance: Appearance,
    ): Color = colors.getValue(path).getValue(appearance)

    companion object {
        /** The paths that leave macOS: `BRAND` in the Rust tree's desktop/design/build.mjs. */
        val BRAND = listOf("color.accent", "color.status", "space", "radius", "typography")

        private const val EXTENSION = "com.github.pyrlyn.ketch"

        /** The copy the build put on the classpath. */
        val brand: Tokens by lazy {
            val text = checkNotNull(Tokens::class.java.getResource("/tokens.json")) { "tokens.json is not on the classpath" }.readText()
            parse(text)
        }

        fun parse(text: String): Tokens {
            val root = Json.parseToJsonElement(text).jsonObject
            val tokens = mutableMapOf<String, JsonObject>()
            collect(root, "", tokens)
            val brand = tokens.filterKeys { path -> BRAND.any { path == it || path.startsWith("$it.") } }

            fun group(prefix: String) = brand.filterKeys { it.startsWith("$prefix.") }.mapKeys { it.key.removePrefix("$prefix.") }
            return Tokens(
                colors = group("color").mapValues { (_, token) -> Appearance.entries.associateWith { color(token, it, tokens) } },
                space = group("space").mapValues { pixels(it.value.getValue("\$value")).dp },
                radius = group("radius").mapValues { pixels(it.value.getValue("\$value")).dp },
                type = group("typography").mapValues { typeToken(it.value.getValue("\$value").jsonObject) },
            )
        }

        private fun collect(
            node: JsonObject,
            path: String,
            into: MutableMap<String, JsonObject>,
        ) {
            if ("\$value" in node) {
                into[path] = node
                return
            }
            for ((key, child) in node) {
                if (!key.startsWith("$") && child is JsonObject) collect(child, if (path.isEmpty()) key else "$path.$key", into)
            }
        }

        private fun color(
            token: JsonObject,
            appearance: Appearance,
            all: Map<String, JsonObject>,
        ): Color {
            val variants = token["\$extensions"]?.jsonObject?.get(EXTENSION)?.jsonObject
            val value = appearance.keys.firstNotNullOfOrNull { variants?.get(it) } ?: token.getValue("\$value")
            // An alias resolves in the appearance it is read in, so it follows the dark accent in dark mode.
            val alias = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (alias != null) {
                val target = all[alias.removeSurrounding("{", "}")] ?: error("tokens.json: $alias names no token")
                return color(target, appearance, all)
            }
            val c = value.jsonObject
            val (r, g, b) = c.getValue("components").jsonArray.map { it.jsonPrimitive.float }
            return Color(r, g, b, c["alpha"]?.jsonPrimitive?.float ?: 1f)
        }

        private fun pixels(value: JsonElement): Float {
            val dimension = value.jsonObject
            require(dimension["unit"]?.jsonPrimitive?.content == "px") { "tokens.json: $value is not in px" }
            return dimension.getValue("value").jsonPrimitive.float
        }

        private fun typeToken(value: JsonObject) =
            TypeToken(
                size = pixels(value.getValue("fontSize")),
                weight = value.getValue("fontWeight").jsonPrimitive.int,
                lineHeight = value.getValue("lineHeight").jsonPrimitive.float,
                letterSpacing = value["letterSpacing"]?.let(::pixels) ?: 0f,
            )
    }
}
