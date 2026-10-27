package ketch.core.text

/**
 * Plain text helpers the core and its renderers share. Kept apart from any renderer because the core needs them
 * to build its own messages — a download's size, a server's error snippet — and a front end needs the same
 * spelling so its output matches what the core said.
 */

private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")
private const val KIB = 1024.0
private const val TEN = 10.0
private const val TEN_L = 10L
private const val BMP_MAX = 0xFFFF
private const val SUPPLEMENTARY = 0x10000
private const val HIGH_BASE = 0xD800
private const val LOW_BASE = 0xDC00
private const val SURROGATE_BITS = 10

/** Human-readable byte count. */
fun bytes(n: Long): String {
    var value = n.toDouble()
    var unit = 0
    while (value >= KIB && unit < UNITS.size - 1) {
        value /= KIB
        unit += 1
    }
    return if (unit == 0) "$n B" else "${oneDecimal(value)} ${UNITS[unit]}"
}

/** `{:.1}` as Rust formats it: round half to even on the binary value, one digit after the point. */
fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * TEN).toLong()
    return "${tenths / TEN_L}.${tenths % TEN_L}"
}

/** Shorten to [width] characters, ending with `…` when it does not fit. Counts code points, as Rust's `chars()`. */
fun truncate(
    text: String,
    width: Int,
): String {
    val points = codePoints(text)
    if (points.size <= width) return text
    val keep = (width - 1).coerceAtLeast(0)
    return points.take(keep).joinToString("") + "…"
}

/** Code points of [text], each as its own string, so surrogate pairs are never split. */
fun codePoints(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val step = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        out += text.substring(i, i + step)
        i += step
    }
    return out
}

/**
 * Lines as Rust's `str::lines` splits them: on `\n`, a trailing `\r` dropped from each line, and no empty last
 * line for a text that ends with a newline.
 */
fun rustLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    val lines = if (text.endsWith('\n')) parts.dropLast(1) else parts
    return lines.map { it.removeSuffix("\r") }
}

/**
 * Strings in Rust's order: by UTF-8 bytes, which is code point order. Kotlin's own `compareTo` orders UTF-16
 * units, which puts a character above U+FFFF before U+E000–U+FFFF; sorted output must match Rust ketch's.
 */
val BYTE_ORDER: Comparator<String> =
    Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i)
            val cb = codePointAt(b, j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += if (ca > BMP_MAX) 2 else 1
            j += if (cb > BMP_MAX) 2 else 1
        }
        (a.length - i).compareTo(b.length - j)
    }

internal fun codePointAt(
    s: String,
    i: Int,
): Int {
    val c = s[i]
    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        return SUPPLEMENTARY + ((c.code - HIGH_BASE) shl SURROGATE_BITS) + (s[i + 1].code - LOW_BASE)
    }
    return c.code
}

/** Rust's `to_ascii_lowercase`: only A–Z fold, so lengths and offsets never change. */
fun String.asciiLowercase(): String = buildString(length) { for (c in this@asciiLowercase) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }

/** Rust's `is_ascii_alphanumeric`; Kotlin's `isLetterOrDigit` takes every script. */
fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** Rust's `to_ascii_uppercase`. */
fun String.asciiUppercase(): String = buildString(length) { for (c in this@asciiUppercase) append(if (c in 'a'..'z') c - ('a' - 'A') else c) }

/**
 * Rust's `eq_ignore_ascii_case`. Kotlin's `equals(ignoreCase = true)` folds every script, so `İ` would match `i`
 * where Rust ketch says it does not.
 */
fun String.eqIgnoreAsciiCase(other: String): Boolean = length == other.length && asciiLowercase() == other.asciiLowercase()

/**
 * Unicode `White_Space`, which Rust's `char::is_whitespace` and `str::trim` use. Kotlin's `isWhitespace` also
 * counts U+001C–U+001F and misses U+0085, so a name Rust refuses would pass.
 */
fun Char.isRustWhitespace(): Boolean =
    this in '\u0009'..'\u000d' ||
        this == ' ' ||
        this == '\u0085' ||
        this == ' ' ||
        this == ' ' ||
        this in ' '..' ' ||
        this == ' ' ||
        this == ' ' ||
        this == ' ' ||
        this == ' ' ||
        this == '　'

/** Rust's `str::trim`. */
fun String.rustTrim(): String = trim { it.isRustWhitespace() }

/** Words between runs of whitespace, as Rust's `split_whitespace`: no empty words. */
fun String.splitWhitespace(): List<String> =
    buildList {
        var start = -1
        for ((i, c) in this@splitWhitespace.withIndex()) {
            if (c.isRustWhitespace()) {
                if (start >= 0) add(this@splitWhitespace.substring(start, i))
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) add(this@splitWhitespace.substring(start))
    }

/**
 * Rust's `str::escape_debug`, which error messages use to show a name with its invisible characters spelled out.
 * Printability follows Unicode categories; a character outside the BMP is printable unless it is private use or a
 * tag, which is where Rust's table and these categories meet for anything a file name carries.
 */
fun escapeDebug(text: String): String =
    buildString {
        codePoints(text).forEachIndexed { i, point ->
            when (point) {
                "\u0000" -> append("\\0")
                "\t" -> append("\\t")
                "\r" -> append("\\r")
                "\n" -> append("\\n")
                "\\", "'", "\"" -> append('\\').append(point)
                else -> if (printable(point, i == 0)) append(point) else append("\\u{${codePointAt(point, 0).toString(HEX)}}")
            }
        }
    }

private const val HEX = 16
private const val PRIVATE_PLANES = 0xF0000
private val TAGS = 0xE0000..0xE0FFF

private val UNPRINTABLE =
    setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.SURROGATE,
        CharCategory.PRIVATE_USE,
        CharCategory.UNASSIGNED,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
        CharCategory.SPACE_SEPARATOR,
    )

// Rust escapes a combining mark only where it would attach to the opening quote.
private fun printable(
    point: String,
    first: Boolean,
): Boolean {
    if (point.length > 1) return codePointAt(point, 0).let { it < PRIVATE_PLANES && it !in TAGS }
    val c = point[0]
    val combining = c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.ENCLOSING_MARK
    return c == ' ' || (c.category !in UNPRINTABLE && !(first && combining))
}
