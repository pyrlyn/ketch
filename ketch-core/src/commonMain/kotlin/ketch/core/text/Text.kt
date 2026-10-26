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

private fun codePointAt(
    s: String,
    i: Int,
): Int {
    val c = s[i]
    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        return SUPPLEMENTARY + ((c.code - HIGH_BASE) shl SURROGATE_BITS) + (s[i + 1].code - LOW_BASE)
    }
    return c.code
}
