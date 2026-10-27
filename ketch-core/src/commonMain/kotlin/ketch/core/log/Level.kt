// What the log file takes and how its records read; the file itself is in nativeMain.
package ketch.core.log

import ketch.core.text.asciiLowercase
import ketch.core.text.rustTrim
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How much gets written. Ordered so a record is written when its level is at or below the configured one,
 * which puts [Off] first and makes it filter everything.
 *
 * A `config.toml` value: written in lower case, read through [parse], so the file takes every spelling
 * `KETCH_LOG_LEVEL` takes, in any case.
 */
@Serializable(with = LevelSerializer::class)
enum class Level(
    internal val label: String,
) {
    Off("OFF"),
    Error("ERROR"),
    Warn("WARN"),
    Info("INFO"),
    Debug("DEBUG"),
    ;

    /** Whether a record at this level is written to a log configured at [configured]. */
    internal fun passes(configured: Level) = this <= configured && configured != Off

    override fun toString() = label.asciiLowercase()

    companion object {
        val DEFAULT = Info

        /** Rust `FromStr`: the names people type; anything else throws, listing what is allowed. */
        fun parse(text: String): Level =
            when (val name = text.rustTrim().asciiLowercase()) {
                "off", "none", "false" -> Off
                "error" -> Error
                "warn", "warning" -> Warn
                "info" -> Info
                "debug", "trace" -> Debug
                else -> throw IllegalArgumentException("unknown log level `$name`; use off, error, warn, info or debug")
            }
    }
}

/**
 * How a record is written: the line format every person can read, or JSON Lines, which a log shipper wants and
 * `jq` reads. Serialised and read as [Level] is.
 */
@Serializable(with = FormatSerializer::class)
enum class Format {
    Text,
    Json,
    ;

    override fun toString() = name.asciiLowercase()

    companion object {
        val DEFAULT = Text

        fun parse(text: String): Format =
            when (val name = text.rustTrim().asciiLowercase()) {
                "text", "plain", "logfmt" -> Text
                "json", "jsonl", "ndjson" -> Json
                else -> throw IllegalArgumentException("unknown log format `$name`; use text or json")
            }
    }
}

/** Reading goes through `parse`, not the enum names, so the file accepts exactly what the variable does. */
abstract class ParsedSerializer<T : Any>(
    name: String,
    private val parse: (String) -> T,
) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): T =
        try {
            parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message, e)
        }
}

object LevelSerializer : ParsedSerializer<Level>("ketch.core.log.Level", Level::parse)

object FormatSerializer : ParsedSerializer<Format>("ketch.core.log.Format", Format::parse)

/**
 * One record on one line, so a log stays greppable and every reader from `tail` to a shipper agrees where a
 * record ends. Control characters go because this file gets `cat`ed.
 */
internal fun escape(message: String): String =
    buildString(message.length) {
        for (c in message) {
            when {
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c.isISOControl() -> Unit
                else -> append(c)
            }
        }
    }

private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

/** Unix seconds as an RFC 3339 UTC timestamp, `1970-01-01T00:00:00Z`. */
@Suppress("MagicNumber")
fun timestamp(secs: Long): String {
    val days = secs.floorDiv(SECONDS_PER_DAY)
    val rest = secs.mod(SECONDS_PER_DAY)
    val (y, m, d) = civilFromDays(days)
    return "${pad(y, 4)}-${pad(m, 2)}-${pad(d, 2)}T${pad(rest / SECONDS_PER_HOUR, 2)}:" +
        "${pad(rest % SECONDS_PER_HOUR / SECONDS_PER_MINUTE, 2)}:${pad(rest % SECONDS_PER_MINUTE, 2)}Z"
}

private fun pad(
    n: Long,
    width: Int,
) = n.toString().padStart(width, '0')

/**
 * Days since the epoch to a calendar date, by Howard Hinnant's `civil_from_days`. Written out rather than pulled
 * in, as in Rust ketch: a date library is a dependency, and this is the only date ketch formats.
 */
@Suppress("MagicNumber")
private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return Triple(y, m, d)
}
