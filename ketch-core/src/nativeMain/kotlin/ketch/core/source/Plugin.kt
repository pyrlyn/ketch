// External source plugins. A plugin is any executable named `ketch-source-<scheme>` found in the plugins directory or
// on PATH. ketch invokes it with a subcommand and reads one JSON document from stdout. That is the entire contract:
// plugins can be written in any language and need no ketch release to ship. The protocol is specified in
// `docs/PLUGINS.md`; PROTOCOL_VERSION is what this build speaks.
//
//   capabilities              -> {"protocol":1,"scheme":"gitlab","download":false,"search":true}
//   describe <id>             -> a SourceInfo object, or null
//   releases <id> [--prerelease] [--limit N]
//                             -> [ {"tag":"v1.2.3","version":"1.2.3","assets":[...]}, ... ]
//   search <query> --limit N  -> [ SourceInfo, ... ]
//   download <url> <dest>     -> only when capabilities.download is true
package ketch.core.source

import fs.joinPath
import ketch.core.cancel.Cancel
import ketch.core.config.envOs
import ketch.core.config.splitPathVar
import ketch.core.error.KetchError
import ketch.core.http.API_JSON
import ketch.core.http.DEFAULT_MAX_DOWNLOAD
import ketch.core.http.Http
import ketch.core.http.sha256File
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.SourceInfo
import ketch.core.platform.host
import ketch.core.report.Ctx
import ketch.core.report.ProgressSink
import ketch.core.report.Report
import ketch.core.text.BYTE_ORDER
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import process.Command
import process.Output
import process.ProcessException
import process.runBounded
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val PROTOCOL_VERSION = 1

/** Executable prefix a plugin must use to be discovered. */
const val PLUGIN_PREFIX = "ketch-source-"

/** How long a plugin has to answer one subcommand. */
private val PLUGIN_TIMEOUT = 30.seconds

/** How much it may write to one pipe while doing so. */
internal const val PLUGIN_MAX_OUTPUT = 8L shl 20

@Serializable
private data class Capabilities(
    val protocol: Int,
    val scheme: String,
    /** The plugin fetches assets itself, e.g. because they need credentials. */
    val download: Boolean = false,
    val search: Boolean = false,
)

/** A discovered plugin executable. */
class PluginSource private constructor(
    val path: Path,
    private val scheme: String,
    private val downloads: Boolean,
    private val searches: Boolean,
    /** Where ketch's own download of a URL the plugin hands back is noted. */
    private val report: Report,
    /** The configured cap on ketch's own download of such a URL. */
    private val maxDownload: Long,
) : Source {
    /** File name of the executable, for diagnostics. */
    val name get() = fileName(path)

    private fun <T> run(
        args: List<String>,
        strategy: DeserializationStrategy<T>,
    ): T = parse(path, output(path, args), strategy)

    override fun scheme() = scheme

    override fun describe(id: String): SourceInfo? = run(listOf("describe", id), SourceInfo.serializer().nullable)

    override fun listReleases(
        id: String,
        opts: ListOpts,
    ): List<Release> {
        val args = listOf("releases", id, "--limit", opts.limit.toString()) + listOfNotNull("--prerelease".takeIf { opts.includePrerelease })
        // The interface promises drafts are gone. Prereleases are dropped only when something stable is there to
        // prefer, matching GitHub: a project that has never cut a stable tag must still be installable.
        val releases = run(args, ListSerializer(Release.serializer())).filter { !it.draft }
        return if (!opts.includePrerelease && releases.any { !it.prerelease }) releases.filter { !it.prerelease } else releases
    }

    override fun download(
        asset: ReleaseAsset,
        dest: Path,
        progress: ProgressSink,
        cancel: Cancel,
    ): String {
        cancel.check()
        if (!downloads) {
            if (!allowedAssetUrl(asset.url)) throw KetchError.Plugin(name, "refusing to fetch ${asset.url}", "")
            // No token is ever handed to a plugin's URLs: whatever credentials an asset needs must come from the
            // plugin's own headers.
            return Http(null, report, maxDownload).download(asset.url, dest, asset.headers, false, progress, cancel)
        }
        output(path, listOf("download", asset.url, dest.toString()))
        if (!SystemFileSystem.exists(dest)) throw KetchError.Plugin(name, "reported success but wrote no file to $dest", "")
        // Hash what actually landed on disk. A plugin does not get to assert the checksum of its own download.
        return sha256File(dest)
    }

    override fun search(
        query: String,
        limit: Int,
    ): List<SourceInfo> = if (searches) run(listOf("search", query, "--limit", limit.toString()), ListSerializer(SourceInfo.serializer())) else emptyList()

    companion object {
        /** Interrogate an executable and adopt it if it speaks a version this ketch knows. */
        fun probe(
            path: Path,
            report: Report,
            maxDownload: Long = DEFAULT_MAX_DOWNLOAD,
        ): PluginSource {
            val caps = parse(path, output(path, listOf("capabilities")), Capabilities.serializer())
            if (caps.protocol != PROTOCOL_VERSION) {
                throw KetchError.Plugin(fileName(path), "speaks protocol ${caps.protocol} but this ketch speaks $PROTOCOL_VERSION", "")
            }
            // The scheme ends up in user input and in recorded state, so it has to be something that can be typed and
            // round-tripped unambiguously.
            if (caps.scheme.isEmpty() || !caps.scheme.all(::isSchemeChar)) {
                throw KetchError.Plugin(fileName(path), "reports an unusable scheme `${caps.scheme}`", "")
            }
            return PluginSource(path, caps.scheme, caps.download, caps.search, report, maxDownload)
        }
    }
}

/**
 * Find every plugin available to this run: one entry per candidate, so a single broken plugin can be reported without
 * hiding the ones that work. The plugins dir comes first, so it wins over a copy on PATH.
 */
fun discover(cx: Ctx): List<Result<PluginSource>> {
    val platform = host()
    val dirs = listOf(cx.cfg.pluginDir) + (envOs("PATH")?.let(::splitPathVar)?.map(::Path) ?: emptyList())
    val seen = mutableSetOf<String>()
    val found = mutableListOf<Result<PluginSource>>()
    for (dir in dirs) {
        // Directory order is arbitrary; a stable list keeps `plugin list` and any shadowing warning reproducible.
        val names = pluginNames(dir).sortedWith(BYTE_ORDER)
        for (name in names) {
            val path = joinPath(dir, name)
            if (name in seen || !platform.isExecutable(path)) continue
            seen += name
            found +=
                try {
                    Result.success(
                        PluginSource.probe(
                            path,
                            cx.report,
                            cx.cfg.maxDownload
                                .coerceAtMost(Long.MAX_VALUE.toULong())
                                .toLong(),
                        ),
                    )
                } catch (e: KetchError) {
                    Result.failure(e)
                }
        }
    }
    return found
}

/** The names in [dir] that a plugin could have; none for a directory that cannot be read. */
// A PATH entry that is missing or unreadable is normal, not a failure worth reporting.
@Suppress("SwallowedException")
private fun pluginNames(dir: Path): List<String> =
    try {
        SystemFileSystem.list(dir).map { it.name }.filter { it.startsWith(PLUGIN_PREFIX) && it.length > PLUGIN_PREFIX.length }
    } catch (_: IOException) {
        emptyList()
    }

private fun isSchemeChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'

private fun fileName(path: Path) = path.name.ifEmpty { "plugin" }

/**
 * URLs ketch itself will fetch on a plugin's behalf. HTTPS is the production path. Loopback HTTP is how tests and a
 * local plugin serve fixtures; anything else (`http://evil.example`, file URLs, a `localhost` prefix that is really
 * another host) is refused.
 */
internal fun allowedAssetUrl(url: String): Boolean {
    val https = listOf("https://", "HTTPS://").firstOrNull { url.startsWith(it) }
    val http = listOf("http://", "HTTP://").firstOrNull { url.startsWith(it) }
    return when {
        https != null -> url.length > https.length
        http != null -> loopbackHost(url.substring(http.length))
        else -> false
    }
}

private fun loopbackHost(rest: String): Boolean {
    val authority = rest.split('/', '?', '#').first()
    val hostport = authority.substringAfterLast('@')
    return hostport == "127.0.0.1" || hostport == "localhost" || hostport.startsWith("127.0.0.1:") || hostport.startsWith("localhost:")
}

/**
 * Run one plugin subcommand and return its stdout. A plugin is a third-party executable, so this is a trust boundary:
 * no stdin, so a plugin cannot sit waiting on a terminal nobody is typing at; a bound on how much it may write, so it
 * cannot exhaust memory; and a deadline, after which its whole process group is killed. Without them a single
 * misbehaving plugin hangs every ketch command, because discovery probes all of them before anything else runs.
 */
private fun output(
    path: Path,
    args: List<String>,
) = runPlugin(path, args, PLUGIN_TIMEOUT)

internal fun pluginFail(
    path: Path,
    detail: String,
    stderr: ByteArray,
    cause: Throwable? = null,
) = KetchError.Plugin(fileName(path), detail, stderr.decodeToString(), cause)

internal fun runPlugin(
    path: Path,
    args: List<String>,
    timeout: Duration,
): String {
    val command = Command(path.toString(), args).env("KETCH_PROTOCOL_VERSION", PROTOCOL_VERSION.toString())
    val out =
        try {
            command.runBounded(timeout, PLUGIN_MAX_OUTPUT)
        } catch (e: ProcessException) {
            throw pluginFail(path, e.detail, e.stderr, e)
        }
    checkFinished(path, args, out)
    return try {
        out.stdout.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw pluginFail(path, "wrote output that is not UTF-8: ${e.message}", out.stderr, e)
    }
}

/** A plugin that wrote past the cap or exited with a failure has no answer to read. */
private fun checkFinished(
    path: Path,
    args: List<String>,
    out: Output,
) {
    if (out.stdout.size > PLUGIN_MAX_OUTPUT) throw pluginFail(path, "wrote more than $PLUGIN_MAX_OUTPUT bytes to stdout", out.stderr)
    if (!out.status.success) {
        throw KetchError.Command("${fileName(path)} ${args.joinToString(" ")}", out.status.toString(), out.stderr.decodeToString())
    }
}

private fun <T> parse(
    path: Path,
    body: String,
    strategy: DeserializationStrategy<T>,
): T =
    try {
        API_JSON.decodeFromString(strategy, body)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException, so this covers both malformed JSON and a wrong shape.
        throw KetchError.Plugin(fileName(path), "returned JSON ketch cannot read: ${e.message}", "", e)
    }
