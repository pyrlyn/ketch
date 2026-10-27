package ketch.core.model

import ketch.core.text.BYTE_ORDER
import ketch.core.text.asciiLowercase
import ketch.core.text.codePoints
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.isAsciiAlphanumeric
import ketch.core.text.rustTrim
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import semver.VersionKey

/** A version string that orders like semver when it can, and like a human reading digits when it cannot. */
@Serializable(with = VersionSerializer::class)
class Version private constructor(
    val raw: String,
    internal val sem: Semver?,
) : Comparable<Version> {
    /** True when this is a prerelease according to semver metadata. */
    val isPrerelease: Boolean get() = sem?.key?.pre?.isNotEmpty() == true

    /** True when semver, strict or relaxed, could read it. */
    val isSemver: Boolean get() = sem != null

    /** Compare ignoring a leading `v`, for matching a user-supplied tag. */
    fun matchesRequest(requested: String): Boolean = raw.trimStart('v', 'V').eqIgnoreAsciiCase(requested.rustTrim().trimStart('v', 'V'))

    override fun compareTo(other: Version): Int {
        // A leading `v` is tag noise: semver strips it at parse time, and the raw fallback must too, or `0.4.0`
        // and `v0.4.0` look unequal and self-update claims a fake upgrade.
        val left = raw.trimStart('v', 'V')
        val right = other.raw.trimStart('v', 'V')
        // Relaxing is lossy (`1.2.3.4` and `1.2.3.5` both become `1.2.3`) and semver ignores build metadata, so
        // the raw strings break a tie: two different releases never compare equal.
        val bySemver = if (sem != null && other.sem != null) sem.compareTo(other.sem) else 0
        return if (bySemver != 0) bySemver else naturalCmp(left, right)
    }

    override fun equals(other: Any?) = other is Version && other.raw == raw

    override fun hashCode() = raw.hashCode()

    override fun toString() = raw

    companion object {
        fun parse(raw: String): Version {
            val trimmed = raw.rustTrim()
            val core = trimmed.trimStart('v', 'V')
            return Version(trimmed, Semver.parse(core) ?: relaxedSemver(core))
        }
    }
}

/** Strict SemVer 2.0 as Rust's `semver` crate reads it, ordered as that crate orders it: build metadata last. */
internal class Semver(
    val key: VersionKey,
    private val build: List<String>,
) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int {
        val byKey = key.compareTo(other.key)
        return if (byKey != 0) byKey else compareBuild(build, other.build)
    }

    companion object {
        private const val NUM = "(?:0|[1-9][0-9]*)"
        private const val PRE_ID = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
        private const val BUILD_ID = "[0-9A-Za-z-]+"
        private val STRICT = Regex("^$NUM\\.$NUM\\.$NUM(?:-$PRE_ID(?:\\.$PRE_ID)*)?(?:\\+($BUILD_ID(?:\\.$BUILD_ID)*))?$")

        fun parse(text: String): Semver? {
            val build = STRICT.matchEntire(text)?.groupValues?.get(1)
            val key = build?.let { VersionKey.parse(text) }
            return key?.let { Semver(it, build.takeIf(String::isNotEmpty)?.split('.').orEmpty()) }
        }

        // Numeric identifiers by value, then the one with fewer leading zeros; numeric before alphanumeric.
        private fun compareBuild(
            a: List<String>,
            b: List<String>,
        ): Int {
            a.zip(b).forEach { (x, y) ->
                val c = compareBuildId(x, y)
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }

        private fun compareBuildId(
            x: String,
            y: String,
        ): Int {
            val xNum = x.all { it in '0'..'9' }
            val yNum = y.all { it in '0'..'9' }
            return when {
                xNum != yNum -> {
                    if (xNum) -1 else 1
                }

                !xNum -> {
                    x.compareTo(y)
                }

                else -> {
                    compareValuesBy(x.trimStart('0'), y.trimStart('0'), { it.length }, { it }).takeIf { it != 0 }
                        ?: x.length.compareTo(y.length)
                }
            }
        }
    }
}

private const val SEMVER_PARTS = 3

/** Accept `1`, `1.2` and `1.2.3.4` by padding or truncating to three parts. */
private fun relaxedSemver(text: String): Semver? {
    val head = text.takeWhile { it in '0'..'9' || it == '.' }
    val parts =
        head
            .trimEnd('.')
            .split('.')
            .filter { it.isNotEmpty() }
            .take(SEMVER_PARTS)
    if (parts.isEmpty()) return null
    val base = (parts + List(SEMVER_PARTS - parts.size) { "0" }).joinToString(".")
    val suffix = text.substring(head.length).trimStart('-', '_', '+')
    // Separators semver rejects inside a prerelease tag become dots.
    val cleaned = suffix.map { if (it.isAsciiAlphanumeric() || it == '.') it else '.' }
    val candidate = if (suffix.isEmpty()) base else "$base-${cleaned.joinToString("").trim('.')}"
    return Semver.parse(candidate)
}

private const val U128_MAX = "340282366920938463463374607431768211455"

/** Compare strings the way a person reads them: digit runs numerically, everything else ignoring ASCII case. */
internal fun naturalCmp(
    a: String,
    b: String,
): Int {
    val x = codePoints(a)
    val y = codePoints(b)
    var i = 0
    var j = 0
    var order = 0
    while (order == 0 && i < x.size && j < y.size) {
        if (digit(x[i]) && digit(y[j])) {
            val xs = run(x, i)
            val ys = run(y, j)
            i += xs.length
            j += ys.length
            order = compareValuesBy(number(xs), number(ys), { it.length }, { it })
        } else {
            order = BYTE_ORDER.compare(x[i].asciiLowercase(), y[j].asciiLowercase())
            i += 1
            j += 1
        }
    }
    return if (order != 0) order else (x.size - i).compareTo(y.size - j)
}

private fun digit(point: String) = point.length == 1 && point[0] in '0'..'9'

private fun run(
    points: List<String>,
    from: Int,
): String {
    var end = from
    while (end < points.size && digit(points[end])) end += 1
    return points.subList(from, end).joinToString("")
}

// Rust reads a run as u128 and takes 0 for one that does not fit.
private fun number(digits: String): String {
    val n = digits.trimStart('0')
    val fits = n.length < U128_MAX.length || (n.length == U128_MAX.length && n <= U128_MAX)
    return if (fits) n else ""
}

internal object VersionSerializer : KSerializer<Version> {
    override val descriptor = PrimitiveSerialDescriptor("ketch.Version", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Version,
    ) = encoder.encodeString(value.raw)

    override fun deserialize(decoder: Decoder) = Version.parse(decoder.decodeString())
}
