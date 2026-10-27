package ketch.core.listing

import fs.FsException
import fs.readText
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.fsio.writePrivate
import ketch.core.install.releaseOf
import ketch.core.model.Release
import ketch.core.model.nowUnix
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.SourceRegistry
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How long a looked-up `latest` is reused, in seconds. Long enough that listing twice in a row, or piping one listing
 * into another command, does not spend the GitHub rate limit twice; short enough that a release published over lunch
 * shows up after it.
 */
const val CACHE_TTL_SECS: Long = 10 * 60

/** The cache file, under the ketch cache directory. */
internal const val CACHE_FILE = "latest.json"

/**
 * The shape of the cache file. A file without it, or with another number, is from a ketch that keyed or recorded its
 * answers differently, and is read as empty rather than misread.
 */
internal const val CACHE_FORMAT = 2

/**
 * The release a lookup found, kept to what the update rule reads: notes are dropped, and assets are kept, as names
 * and digests, only for a floating tag, where they are how a new build is told from the installed one.
 */
@Serializable
@JsonIgnoreUnknownKeys
data class Found(
    val release: Release,
) {
    companion object {
        fun of(
            release: Release,
            keepAssets: Boolean,
        ): Found {
            val assets = if (keepAssets) release.assets.map { it.copy(url = "", headers = emptyMap(), contentType = null) } else emptyList()
            return Found(release.copy(notes = null, assets = assets))
        }
    }
}

/**
 * Fill in `latest` for every row that has an upstream.
 *
 * Each distinct source is asked once, and a fresh cached answer is used instead of asking. A source that fails leaves
 * its rows `Unreachable`; nothing here throws, because one missing version must not hide every other row.
 */
fun fillLatest(
    cx: Ctx,
    sources: SourceRegistry,
    rows: List<Row>,
): Outcome {
    val lookups =
        rows
            .mapNotNull { it.lookup() }
            .distinctBy { it.key() }
            .sortedBy { it.key() }
            .associateBy { it.key() }
    if (lookups.isEmpty()) return Outcome()

    val path = cachePath(cx.cfg)
    val now = nowUnix()
    val cache = Cache.load(path, cx.report).fresh(now)
    val answers = HashMap<String, Found>()
    val entries = cache.entries.toMutableMap()
    val stale = lookups.filter { (key, _) -> entries[key]?.also { answers[key] = it.found } == null }

    val fetched = askAll(cx, sources, stale)
    for ((key, result) in fetched) {
        result
            .onSuccess { found ->
                entries[key] = CacheEntry(found, now)
                answers[key] = found
            }.onFailure { cx.report.debug("latest for $key: ${it.message}") }
    }
    if (fetched.any { it.second.isSuccess }) Cache(CACHE_FORMAT, entries.toList().sortedBy { it.first }.toMap()).save(path, cx.report)

    for (row in rows) {
        val lookup = row.lookup() ?: continue
        row.latest = answers[lookup.key()]?.let(Latest::Known) ?: Latest.Unreachable
        // Decided once here so that a draft that is gone is said once, however many times the row is rendered.
        row.newer(cx.report)
    }
    return Outcome(lookups.size, answers.size)
}

/**
 * Fill in `latest` from earlier listings' answers alone, asking no source. For callers that show a `latest` beside
 * something else — search results — where a lookup per row would cost a network round trip each for a value that is
 * only a hint. A row the cache has no fresh answer for stays `NotChecked`, which reads as unknown.
 */
fun fillCached(
    cx: Ctx,
    rows: List<Row>,
) {
    val cache = Cache.load(cachePath(cx.cfg), cx.report).fresh(nowUnix())
    for (row in rows) {
        row.lookup()?.let { cache.entries[it.key()] }?.let { row.latest = Latest.Known(it.found) }
    }
}

/**
 * The lookups the cache could not answer, with a counter on stderr while they run. One at a time, where Rust asks
 * `jobs` at once: see `install.batch` for why nothing here runs on two threads.
 */
private fun askAll(
    cx: Ctx,
    sources: SourceRegistry,
    stale: Map<String, Lookup>,
): List<Pair<String, Result<Found>>> {
    if (stale.isEmpty()) return emptyList()
    return cx.report.counter("checking", stale.size.toLong(), "packages").use { counter ->
        // A sequence, so a stop is seen before the next lookup rather than after the last.
        stale.entries
            .asSequence()
            .takeWhile { !cx.cancel.isCancelled }
            .map { (key, lookup) ->
                val result =
                    try {
                        Result.success(Found.of(releaseOf(sources, lookup.source, lookup.tag, lookup.opts()), lookup.tag != null))
                    } catch (e: KetchError) {
                        Result.failure(e)
                    }
                counter.inc()
                key to result
            }.toList()
    }
}

internal fun cachePath(cfg: Config) = Path(cfg.cacheDir, CACHE_FILE)

@Serializable
@JsonIgnoreUnknownKeys
internal data class CacheEntry(
    val found: Found,
    @SerialName("checked_at") val checkedAt: Long,
) {
    /** Fresh for [CACHE_TTL_SECS]. A timestamp from the future — a clock moved back — is stale rather than fresh for however long the jump was. */
    fun fresh(now: Long) = now >= checkedAt && now - checkedAt < CACHE_TTL_SECS
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val CACHE_JSON =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

/** Answers from earlier listings, keyed by [Lookup.key]. */
@Serializable
@JsonIgnoreUnknownKeys
internal data class Cache(
    /** [CACHE_FORMAT] when written; absent from files that predate it. */
    val format: Int = 0,
    val entries: Map<String, CacheEntry> = emptyMap(),
) {
    fun fresh(now: Long) = copy(entries = entries.filterValues { it.fresh(now) })

    /** Written whole and swapped in, so two listings at once cannot leave a torn file; a failure costs the next listing a lookup, nothing more. */
    fun save(
        path: Path,
        report: Report,
    ) {
        try {
            writePrivate(path, CACHE_JSON.encodeToString(serializer(), copy(format = CACHE_FORMAT)).encodeToByteArray())
        } catch (e: KetchError) {
            report.debug("could not write $path: ${e.message}")
        }
    }

    companion object {
        /** The cache is a convenience: unreadable or malformed means empty, and the next listing simply asks again. */
        fun load(
            path: Path,
            report: Report,
        ): Cache {
            val text =
                try {
                    readText(path)
                } catch (_: FsException) {
                    return Cache()
                }
            return try {
                parse(text)
            } catch (e: SerializationException) {
                report.debug("ignoring $path: ${e.message}")
                Cache()
            } catch (e: IllegalArgumentException) {
                report.debug("ignoring $path: ${e.message}")
                Cache()
            }
        }

        /** The format is read on its own first: an older file's entries need not parse as today's for the file to be recognised and set aside. */
        fun parse(text: String): Cache {
            val format =
                CACHE_JSON
                    .parseToJsonElement(text)
                    .jsonObject["format"]
                    ?.jsonPrimitive
                    ?.int ?: 0
            require(format == CACHE_FORMAT) { "cache format $format, not $CACHE_FORMAT" }
            return CACHE_JSON.decodeFromString(serializer(), text)
        }
    }
}
