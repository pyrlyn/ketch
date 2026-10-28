// The redaction rules of Rust tests/why.rs: scores and table padding vary with the scorer's weights and the host,
// so a snapshot keeps neither.
package ketch.e2e

import ketch.e2e.support.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The score a pin gets, which is the one a snapshot keeps. */
private const val PIN = 2147483647L

/** Replace `…<anything><needle>rest` with `{root}<canonical>rest`. */
internal fun scrubThroughRootSuffix(
    text: String,
    needle: String,
    canonical: String = needle,
): String {
    val out = StringBuilder()
    var rest = text
    while (true) {
        val idx = rest.indexOf(needle)
        if (idx < 0) break
        val before = rest.substring(0, idx)
        // Walk left to the start of the absolute path token.
        val start = before.indexOfLast { it == '"' || it.isWhitespace() || it == '=' } + 1
        out.append(before, 0, start).append("{root}").append(canonical)
        rest = rest.substring(idx + needle.length)
    }
    return out.append(rest).toString()
}

/** Scores (except a pin) and table padding that still reflects pre-redaction widths are normalized. */
internal fun normalizeWhyText(text: String): String {
    var inTable = false
    val lines =
        text.lines().let { if (text.endsWith('\n')) it.dropLast(1) else it }.map { line ->
            val trimmed = line.trimEnd()
            when {
                trimmed == "assets" || trimmed == "rejected" -> {
                    inTable = true
                    trimmed
                }

                trimmed.isEmpty() -> {
                    inTable = false
                    ""
                }

                else -> {
                    if (trimmed.startsWith("checksum") || trimmed.startsWith("trust") || trimmed.startsWith("candidate")) inTable = false
                    if (inTable) normalizeRow(trimmed) else trimmed
                }
            }
        }
    return lines.joinToString("\n") + if (text.endsWith('\n')) "\n" else ""
}

private fun normalizeRow(row: String): String {
    val cells = splitTableCols(row).toMutableList()
    if (cells.size >= 2 && cells[0].all { it.isDigit() } && cells[0] != "$PIN") cells[0] = "{score}"
    return cells.joinToString("  ")
}

/** Split a table row on the two-space column gap without breaking single spaces inside a cell. */
private fun splitTableCols(row: String): List<String> {
    val cols = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < row.length) {
        if (i + 1 < row.length && row[i] == ' ' && row[i + 1] == ' ') {
            val col = row.substring(start, i).trimEnd()
            if (col.isNotEmpty() || cols.isNotEmpty()) cols += col
            while (i < row.length && row[i] == ' ') i++
            start = i
            continue
        }
        i++
    }
    row
        .substring(start)
        .trimEnd()
        .takeIf { it.isNotEmpty() }
        ?.let { cols += it }
    return cols
}

private fun unpinnedScore(score: JsonElement) = if ((score as? JsonPrimitive)?.longOrNull == PIN) score else JsonPrimitive("{score}")

internal fun normalizeWhyJson(value: JsonElement): JsonElement {
    val top = (value as JsonObject).toMutableMap()
    (top["manifest"] as? JsonObject)?.let { manifest ->
        manifest["origin"]?.str?.let { top["manifest"] = JsonObject(manifest + ("origin" to JsonPrimitive(canonicalOrigin(it)))) }
    }
    (top["assets"] as? JsonObject)?.let { assets ->
        (assets["scored"] as? JsonArray)?.let { scored ->
            val items = scored.map { item -> (item as? JsonObject)?.let { o -> o["score"]?.let { JsonObject(o + ("score" to unpinnedScore(it))) } } ?: item }
            top["assets"] = JsonObject(assets + ("scored" to JsonArray(items)))
        }
    }
    (top["candidate"] as? JsonObject)?.let { candidate ->
        candidate["score"]?.let { top["candidate"] = JsonObject(candidate + ("score" to unpinnedScore(it))) }
    }
    return JsonObject(top)
}

/** The `/` and the Windows `\` spelling of each directory under the root a manifest can come from. */
internal val ROOT_NEEDLES = listOf("/manifests/" to "/manifests/", "\\manifests\\" to "/manifests/", "/registry/" to "/registry/", "\\registry\\" to "/registry/")

/**
 * Paths after a scrubbed root keep the host's separators (`{root}/registry/ripgrep\ketch.toml` on Windows); fold
 * them to `/` up to the end of the path token.
 */
internal fun normalizeRootRelativeSeparators(text: String): String {
    val out = StringBuilder()
    var rest = text
    while (true) {
        val idx = rest.indexOf("{root}")
        if (idx < 0) break
        out.append(rest, 0, idx)
        rest = rest.substring(idx)
        val end = rest.indexOfFirst { it.isWhitespace() || it == '"' }.takeIf { it >= 0 } ?: rest.length
        out.append(rest.substring(0, end).replace('\\', '/'))
        rest = rest.substring(end)
    }
    return out.append(rest).toString()
}

private fun canonicalOrigin(origin: String): String {
    for ((needle, canonical) in ROOT_NEEDLES) {
        val idx = origin.indexOf(needle)
        if (idx >= 0) return "{root}$canonical${origin.substring(idx + needle.length).replace('\\', '/')}"
    }
    return if (origin.startsWith("{root}")) origin.replace('\\', '/') else origin
}
