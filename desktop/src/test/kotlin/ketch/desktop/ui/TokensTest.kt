package ketch.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The brand copy of desktop/design/tokens.json, as the theme reads it. */
class TokensTest {
    private val json = Json.parseToJsonElement(File("design/tokens.json").readText()).jsonObject

    @Test
    fun everyBrandColourHasItsOwnValueInEveryAppearance() {
        val tokens = Tokens.brand
        for (group in listOf("accent", "status")) {
            for ((key, node) in json
                .getValue("color")
                .jsonObject
                .getValue(group)
                .jsonObject) {
                if (key.startsWith("$")) continue
                for (appearance in Appearance.entries) {
                    val value = picked(node.jsonObject, appearance) ?: continue
                    assertEquals(expected(value), tokens.color("$group.$key", appearance), "$group.$key in $appearance")
                }
            }
        }
    }

    @Test
    fun theThemeFindsEveryTokenItReads() {
        val tokens = Tokens.brand
        val colours =
            listOf("accent.default", "accent.ink", "accent.on", "accent.subtle") +
                listOf("installed", "update", "busy", "warning", "error").flatMap { listOf("status.$it", "status.${it}Subtle") }
        for (path in colours) for (appearance in Appearance.entries) tokens.color(path, appearance)
        assertTrue(tokens.space.keys.containsAll(listOf("xxs", "xs", "sm", "md", "lg", "xl", "xxl")))
        assertTrue(tokens.radius.keys.containsAll(listOf("xs", "sm", "md", "lg", "xl")))
        assertTrue(tokens.type.keys.containsAll(listOf("display", "title", "title2", "headline", "body", "callout", "caption", "overline", "mono")))
    }

    @Test
    fun onlyTheBrandSubsetLeavesTheDesignFile() {
        val tokens = Tokens.brand
        assertTrue(tokens.colors.keys.all { it.startsWith("accent.") || it.startsWith("status.") }, tokens.colors.keys.toString())
        assertEquals(px("space", "md").dp, tokens.space.getValue("md"))
        assertEquals(px("radius", "lg").dp, tokens.radius.getValue("lg"))
    }

    @Test
    fun anAliasThatNamesNoTokenIsRefused() {
        val broken = """{"color":{"accent":{"default":{"${'$'}type":"color","${'$'}value":"{color.nothing}"}}}}"""
        assertFailsWith<IllegalStateException> { Tokens.parse(broken) }
    }

    @Test
    fun changelogLinksOpenOnlyOnTheWeb() {
        val opened = mutableListOf<String>()
        val handler = WebOnly(Recording(opened))
        val links = listOf("https://github.com/a/b", "HTTP://example.com", "file:///etc/passwd", "javascript:alert(1)", "ketch://install/x", "not a uri")
        links.forEach(handler::openUri)
        assertEquals(listOf("https://github.com/a/b", "HTTP://example.com"), opened)
    }

    private class Recording(
        val into: MutableList<String>,
    ) : UriHandler {
        override fun openUri(uri: String) {
            into += uri
        }
    }

    /** The value [appearance] picks, or null when it is an alias the parser resolves through another token. */
    private fun picked(
        node: JsonObject,
        appearance: Appearance,
    ): JsonObject? {
        val variants = node["\$extensions"]?.jsonObject?.get("com.github.pyrlyn.ketch")?.jsonObject
        return (appearance.keys.firstNotNullOfOrNull { variants?.get(it) } ?: node.getValue("\$value")) as? JsonObject
    }

    /** The colour from its hex, which the design file keeps beside the components; alpha is its own field. */
    private fun expected(value: JsonObject): Color {
        val rgb =
            value
                .getValue("hex")
                .jsonPrimitive.content
                .removePrefix("#")
                .toLong(HEX)
        val alpha = value["alpha"]?.jsonPrimitive?.float ?: 1f
        return Color(rgb or OPAQUE).copy(alpha = alpha)
    }

    private fun px(
        group: String,
        key: String,
    ) = json
        .getValue(group)
        .jsonObject
        .getValue(key)
        .jsonObject
        .getValue("\$value")
        .jsonObject
        .getValue("value")
        .jsonPrimitive.content
        .toFloat()

    private companion object {
        const val HEX = 16
        const val OPAQUE = 0xFF000000L
    }
}
