// HTTP access. One client for the whole process so connections are reused. Downloads hash while they stream, so
// verification costs no extra read of the file.
package ketch.core.http

import fs.TempFile
import fs.createDirAll
import http.HttpBody
import http.HttpClient
import http.HttpException
import http.HttpRequest
import http.HttpSettings
import http.ktor.KtorHttp
import ketch.core.cancel.Cancel
import ketch.core.config.DEFAULT_MAX_DOWNLOAD_MIB
import ketch.core.config.USER_AGENT
import ketch.core.error.KetchError
import ketch.core.error.io
import ketch.core.error.ioAt
import ketch.core.hash.Sha256
import ketch.core.report.Ctx
import ketch.core.report.ProgressSink
import ketch.core.report.Report
import ketch.core.text.bytes
import ketch.core.text.truncate
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

private const val MIB = 1024L * 1024

/** Cap on API response bodies. Release lists are small; anything larger is a sign we are being fed something we should not buffer. */
internal const val MAX_API_BODY = 32 * MIB

/** Cap on a single asset download when no configuration says otherwise. */
internal val DEFAULT_MAX_DOWNLOAD = DEFAULT_MAX_DOWNLOAD_MIB.toLong() * MIB

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val READ_TIMEOUT_MS = 120_000L
private const val CHUNK = 128 * 1024
private const val SNIPPET = 200
private const val NOT_FOUND = 404
private const val FIRST_ERROR_STATUS = 400

/** Like serde: fields ketch does not know are ignored, so a new API field never breaks a parse. */
internal val API_JSON = Json { ignoreUnknownKeys = true }

/**
 * The process-wide client. Only the read timeout bounds a transfer, as ureq's does: a multi-gigabyte asset over a slow
 * link may take as long as it takes while bytes keep arriving.
 */
private val SHARED: HttpClient by lazy {
    KtorHttp.FACTORY.create(HttpSettings(CONNECT_TIMEOUT_MS, Long.MAX_VALUE, READ_TIMEOUT_MS))
}

class Http(
    private val token: String?,
    /** Where each request is noted, for `--verbose` and the log. */
    val report: Report,
    /** Bytes one download may write. Streaming without a bound would fill the disk on a lying or missing Content-Length. */
    internal val maxDownload: Long = DEFAULT_MAX_DOWNLOAD,
    private val client: HttpClient = SHARED,
) {
    constructor(cx: Ctx) : this(
        cx.cfg.githubToken,
        cx.report,
        cx.cfg.maxDownload
            .coerceAtMost(Long.MAX_VALUE.toULong())
            .toLong(),
    )

    /** The same client with another download cap, for a caller holding a `Config` but not a `Ctx`. */
    fun withMaxDownload(bytes: Long) = Http(token, report, bytes, client)

    /** Whether requests made with `authed` carry a token at all. */
    fun hasToken() = token != null

    private fun request(
        url: String,
        accept: String,
        authed: Boolean,
        extra: Map<String, String> = emptyMap(),
    ): HttpRequest {
        val headers = mutableListOf("User-Agent" to USER_AGENT, "Accept" to accept)
        if (authed && token != null) {
            headers += "Authorization" to "Bearer $token"
            headers += "X-GitHub-Api-Version" to "2022-11-28"
        }
        headers += extra.toList()
        return HttpRequest(url, headers)
    }

    /** GET and deserialize JSON. */
    fun <T> getJson(
        url: String,
        authed: Boolean,
        strategy: DeserializationStrategy<T>,
    ): T {
        val body = getString(url, "application/vnd.github+json", authed)
        return try {
            API_JSON.decodeFromString(strategy, body)
        } catch (e: SerializationException) {
            throw KetchError.Parse(url, e.message ?: e.toString(), e)
        } catch (e: IllegalArgumentException) {
            throw KetchError.Parse(url, e.message ?: e.toString(), e)
        }
    }

    inline fun <reified T> getJson(
        url: String,
        authed: Boolean,
    ): T = getJson(url, authed, serializer<T>())

    /** Like [getJson], but null on 404 instead of an error, where a missing resource is an ordinary answer. */
    fun <T> getJsonOpt(
        url: String,
        authed: Boolean,
        strategy: DeserializationStrategy<T>,
    ): T? =
        try {
            getJson(url, authed, strategy)
        } catch (e: KetchError.Http) {
            if (e.status == NOT_FOUND) null else throw e
        }

    inline fun <reified T> getJsonOpt(
        url: String,
        authed: Boolean,
    ): T? = getJsonOpt(url, authed, serializer<T>())

    /** GET a text body (checksum files, plain manifests). */
    fun getText(
        url: String,
        authed: Boolean,
    ) = getString(url, "text/plain, */*", authed)

    /** GET a text asset from an API asset URL, which answers with the asset's metadata unless the request asks for its bytes. */
    fun getAssetText(
        url: String,
        authed: Boolean,
    ) = getString(url, "application/octet-stream", authed)

    private fun getString(
        url: String,
        accept: String,
        authed: Boolean,
    ): String {
        report.debug("GET $url")
        return call(url, request(url, accept, authed)) { body -> readLimited(body, MAX_API_BODY, url) }
    }

    /**
     * Stream [url] to [dest], hashing as it goes, and return the lowercase hex SHA-256 of the bytes written. Checks
     * [cancel] before every chunk and fails with [KetchError.Cancelled] once it fires.
     *
     * The file is written in full or not at all: it is staged next to the destination and renamed, so an interrupted
     * download never looks like a complete one.
     */
    @Suppress("LongParameterList")
    fun download(
        url: String,
        dest: Path,
        headers: Map<String, String>,
        authed: Boolean,
        progress: ProgressSink,
        cancel: Cancel,
    ): String {
        cancel.check()
        report.debug("GET $url -> $dest")
        val req = request(url, "application/octet-stream", authed, headers)
        return runBlocking {
            stream(url, req) { head, body ->
                val total = head.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                if (total != null && total > maxDownload) throw tooLarge(url, maxDownload)
                val label = dest.name.ifEmpty { "download" }
                progress.start(total, label)
                Download(url, dest, label, total, maxDownload, progress, cancel).run(body)
            }
        }
    }

    private fun <T> call(
        url: String,
        req: HttpRequest,
        read: suspend (HttpBody) -> T,
    ): T = runBlocking { stream(url, req) { _, body -> read(body) } }

    /** [HttpClient.stream] with an error status classified and a request that got no response made a network error. */
    private suspend fun <T> stream(
        url: String,
        req: HttpRequest,
        handle: suspend (http.HttpHead, HttpBody) -> T,
    ): T =
        try {
            client.stream(req) { head, body ->
                if (head.status >= FIRST_ERROR_STATUS) throw statusError(url, head.status, body)
                handle(head, body)
            }
        } catch (e: HttpException) {
            throw KetchError.Network(url, e)
        }
}

/** One download in flight: the staged file, the running hash and the count against the cap. */
private class Download(
    val url: String,
    val dest: Path,
    val label: String,
    val total: Long?,
    val max: Long,
    val progress: ProgressSink,
    val cancel: Cancel,
) {
    suspend fun run(body: HttpBody): String {
        val parent = dest.parent ?: Path(".")
        val staged =
            ioAt(parent) {
                createDirAll(parent)
                TempFile.createIn(parent)
            }
        // `staged` deletes itself on close, so stopping anywhere before persist leaves no file.
        staged.use {
            val written = fill(staged, body)
            // A truncated transfer that still returned 200 would otherwise be indistinguishable from success until the
            // checksum stage.
            if (total != null && written.first != total) {
                throw KetchError.Msg("download of $label ended early: got ${written.first} of $total bytes")
            }
            ioAt(dest) { staged.persist(dest) }
            progress.finish("$label (${bytes(written.first)})")
            return written.second
        }
    }

    /** The byte count and the hex hash of everything copied from [body] into [staged]. */
    private suspend fun fill(
        staged: TempFile,
        body: HttpBody,
    ): Pair<Long, String> {
        val hasher = Sha256()
        val buffer = ByteArray(CHUNK)
        var written = 0L
        while (true) {
            cancel.check()
            val n = readBody(body, buffer, url)
            if (n < 0) break
            hasher.update(buffer, 0, n)
            ioAt(staged.path) { staged.write(buffer, n) }
            written += n
            if (written > max) throw tooLarge(url, max)
            progress.advance(n.toLong())
        }
        return written to hasher.hex()
    }
}

private suspend fun readBody(
    body: HttpBody,
    buffer: ByteArray,
    url: String,
): Int =
    try {
        body.read(buffer)
    } catch (e: HttpException) {
        throw KetchError.Io(Path(url), e)
    }

/** The refusal for a download over the cap, naming the setting that raises it. */
private fun tooLarge(
    url: String,
    max: Long,
) = KetchError.Msg("$url is larger than ${bytes(max)} (max_download_mib); refusing the download")

/** An error status, keeping the server's own message when it sent one: GitHub's bodies explain rate limits precisely. */
private suspend fun statusError(
    url: String,
    status: Int,
    body: HttpBody,
): KetchError.Http {
    val text = runCatching { readLimited(body, MAX_API_BODY, url) }.getOrNull()
    return KetchError.Http(url, status, text?.let(::extractMessage)?.takeIf { it.isNotEmpty() })
}

/**
 * Read an API body, refusing a truncated one. Taking [max] bytes and treating them as complete would parse a cut-off
 * JSON or checksum file as if the server had sent it whole; reading one byte past the cap tells the two apart.
 */
internal suspend fun readLimited(
    body: HttpBody,
    max: Long,
    url: String,
): String {
    val out = kotlinx.io.Buffer()
    val buffer = ByteArray(CHUNK)
    while (out.size <= max) {
        val n = readBody(body, buffer, url)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    if (out.size > max) throw KetchError.Msg("$url is larger than $max bytes; refusing a truncated read")
    return try {
        out.readByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw KetchError.Parse(url, e.message ?: "invalid UTF-8", e)
    }
}

/** Pull `message` out of a JSON error body, else return a trimmed snippet. */
internal fun extractMessage(body: String): String? {
    val message = runCatching { API_JSON.parseToJsonElement(body) }.getOrNull()
    ((message as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    return body.trim().takeIf { it.isNotEmpty() }?.let { truncate(it, SNIPPET) }
}

/** Hash a file that is already on disk. */
fun sha256File(path: Path): String =
    try {
        val hasher = Sha256()
        SystemFileSystem.source(path).use { raw ->
            val source = raw.buffered()
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = source.readAtMostTo(buffer)
                if (n < 0) break
                hasher.update(buffer, 0, n)
            }
        }
        hasher.hex()
    } catch (e: kotlinx.io.IOException) {
        throw KetchError.io(path, e)
    }
