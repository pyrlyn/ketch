// The GitHub releases source. Works unauthenticated; a token raises the rate limit and unlocks private repositories
// and, with push access, draft releases.
package ketch.core.source

import ketch.core.cancel.Cancel
import ketch.core.config.env
import ketch.core.config.validateRepo
import ketch.core.error.KetchError
import ketch.core.http.Http
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.SourceInfo
import ketch.core.model.VersionSpec
import ketch.core.report.ProgressSink
import kotlinx.io.files.Path

/** Overridden for GitHub Enterprise, via `KETCH_GITHUB_API`. */
const val DEFAULT_API = "https://api.github.com"

/** How many checksum files one release may cost in requests: fifty assets would otherwise mean fifty round trips before the download. */
private const val MAX_CHECKSUM_FETCHES = 12

/**
 * Upper bound on listing pages walked when an exact tag missed the tag endpoint. GitHub's list is newest-first and 100
 * items per page at most; a tag older than this is treated as missing rather than looping forever if the API keeps
 * returning full pages.
 */
private const val MAX_RELEASE_PAGES = 100

private const val MAX_PER_PAGE = 100
private const val NOT_FOUND = 404

/** The API base every GitHub request is built on: `KETCH_GITHUB_API` when set, so an Enterprise host or a test double can stand in. */
fun apiBase(): String = (env("KETCH_GITHUB_API")?.takeIf { it.isNotBlank() } ?: DEFAULT_API).trimEnd('/')

class GitHubSource internal constructor(
    private val http: Http,
    private val api: String,
) : Source {
    constructor(http: Http) : this(http, apiBase())

    internal fun repoUrl(
        id: String,
        suffix: String,
    ) = "$api/repos/${validateRepo("GitHub repository", id)}$suffix"

    private fun fetchReleasesPage(
        id: String,
        opts: ListOpts,
        page: Int,
    ): List<GhRelease> {
        val perPage = opts.limit.coerceIn(1, MAX_PER_PAGE)
        return http.getJson(repoUrl(id, "/releases?per_page=$perPage&page=$page"), true)
    }

    /**
     * Walk listed releases until [want] matches or the list is exhausted. The tag endpoint is exact; a different
     * spelling only shows up here, and a busy repository's first page is just the newest releases.
     */
    private fun resolveFromListing(
        id: String,
        want: VersionSpec,
        opts: ListOpts,
    ): Release {
        val perPage = opts.limit.coerceIn(1, MAX_PER_PAGE)
        for (page in 1..MAX_RELEASE_PAGES) {
            val raw = fetchReleasesPage(id, opts, page)
            val found = runCatching { pick(id, publishedReleases(raw, opts, api), want, opts) }
            // The last page decides: its refusal is the answer, where an earlier page's only means "look further".
            if (found.isSuccess || raw.size < perPage) return found.getOrThrow()
        }
        throw KetchError.NoRelease(if (want is VersionSpec.Exact) "$id@${want.version}" else id)
    }

    /**
     * Refuse a request for drafts that GitHub would answer without them. GitHub shows a draft only to a token with
     * push access and simply leaves it out of the listing for anyone else, so without this check `--draft` would end
     * in "no release" for a reason nobody could see.
     */
    private fun requireDraftAccess(id: String) {
        if (!http.hasToken()) {
            throw KetchError.Msg(
                "draft releases of $id are visible only to a GitHub token with push access, and none is set: put one in " +
                    "KETCH_GITHUB_TOKEN, GITHUB_TOKEN or GH_TOKEN, or github_token in config.toml",
            )
        }
        val repo = http.getJsonOpt<GhRepo>(repoUrl(id, ""), true)
        val refusal =
            when {
                repo == null -> {
                    "$id is not visible to the GitHub token that is set; draft releases need a token with push access to it"
                }

                repo.permissions?.push != true -> {
                    "the GitHub token that is set has no push access to $id, and GitHub shows draft releases only to tokens that have it"
                }

                else -> {
                    null
                }
            }
        if (refusal != null) throw KetchError.Msg(refusal)
    }

    override fun scheme() = "github"

    override fun describe(id: String): SourceInfo? = http.getJsonOpt<GhRepo>(repoUrl(id, ""), true)?.toInfo()

    override fun listReleases(
        id: String,
        opts: ListOpts,
    ): List<Release> {
        if (opts.includeDraft) requireDraftAccess(id)
        return publishedReleases(fetchReleasesPage(id, opts, 1), opts, api)
    }

    override fun resolve(
        id: String,
        want: VersionSpec,
        opts: ListOpts,
    ): Release {
        // Neither fast path ever returns a draft: GitHub's `latest` and tag endpoints only know published releases.
        // Drafts are found in the listing, which shows them to a token with push access.
        if (opts.includeDraft) requireDraftAccess(id)
        val direct = if (opts.includeDraft) null else directLookup(id, want, opts)
        val widened = optsFor(want, opts)
        return when {
            direct != null -> direct

            want is VersionSpec.Exact -> resolveFromListing(id, want, widened)

            // Access was checked above; listing again would ask GitHub twice.
            opts.includeDraft -> pick(id, publishedReleases(fetchReleasesPage(id, widened, 1), widened, api), want, widened)

            else -> pick(id, listReleases(id, widened), want, widened)
        }
    }

    /**
     * The fast paths: one request against an endpoint that does the selection server-side. `@1.2.3` for a repository
     * that tags `v1.2.3` is the common spelling of the same request, so that one is tried too; other spellings still
     * have to be found in the list.
     */
    private fun directLookup(
        id: String,
        want: VersionSpec,
        opts: ListOpts,
    ): Release? {
        val suffixes =
            when (want) {
                VersionSpec.Latest -> if (opts.includePrerelease) emptyList() else listOf("/releases/latest")
                is VersionSpec.Exact -> listOfNotNull(want.version, vPrefixed(want.version)).map { "/releases/tags/${urlencodePathSegment(it)}" }
            }
        return suffixes.firstNotNullOfOrNull { suffix ->
            http.getJsonOpt<GhRelease>(repoUrl(id, suffix), true)?.takeIf { !it.draft }?.let { releaseFrom(it, api) }
        }
    }

    override fun checksums(
        id: String,
        release: Release,
        wanted: String,
    ): Map<String, String> {
        // Whatever the API already told us costs nothing.
        val out = release.assets.mapNotNull { a -> a.digest?.let { a.name to it.hex } }.toMap(mutableMapOf())
        // The sidecar for the asset actually being installed goes first. With a cap on how many are worth fetching,
        // the one file that decides this install must never be the one left out, and a release can easily publish
        // thirty sidecars with ours near the end. Aggregates count against the cap too: the heuristic that spots them
        // is a name match, and an unbounded number of name matches is an unbounded number of downloads.
        val candidates =
            release.assets
                .sortedBy { sidecarTarget(it.name) != wanted }
                .filter { sidecarTarget(it.name) != null || isAggregateChecksumFile(it.name) }
        if (candidates.size > MAX_CHECKSUM_FETCHES) http.report.debug("stopping after the checksum-file fetch limit")
        var fetchError: KetchError? = null
        for (asset in candidates.take(MAX_CHECKSUM_FETCHES)) {
            try {
                merge(out, asset, fetchChecksumFile(asset, release.draft) ?: continue)
            } catch (e: KetchError) {
                fetchError = KetchError.Msg("could not fetch checksum file ${asset.name}: ${e.message}", e)
            }
        }
        if (wanted !in out && fetchError != null) throw fetchError
        return out
    }

    /**
     * A checksum file's text, or null when it is missing. A missing one is ordinary; a dead network or a 403 is not,
     * and must not be reported as "no published checksum". Authenticated like the assets themselves: a private
     * repository publishes its sidecars privately too. A draft's are API asset URLs, which answer with the asset's
     * JSON unless asked for bytes.
     */
    private fun fetchChecksumFile(
        asset: ReleaseAsset,
        draft: Boolean,
    ): String? =
        try {
            if (draft) http.getAssetText(asset.url, true) else http.getText(asset.url, true)
        } catch (e: KetchError.Http) {
            if (e.status == NOT_FOUND) null else throw e
        }

    private fun merge(
        out: MutableMap<String, String>,
        asset: ReleaseAsset,
        body: String,
    ) {
        val target = sidecarTarget(asset.name)
        if (target != null) {
            sidecarHex(body, target)?.let { out.getOrPut(target) { it } }
        } else {
            parseChecksumFile(body).forEach { (name, hex) -> out.getOrPut(name) { hex } }
        }
    }

    // The token goes on the request, not on the redirect: the client drops Authorization on the cross-host hop to the
    // CDN that actually serves the bytes. What it does carry is the difference between a private repository
    // installing and answering 404.
    override fun download(
        asset: ReleaseAsset,
        dest: Path,
        progress: ProgressSink,
        cancel: Cancel,
    ) = http.download(asset.url, dest, asset.headers, true, progress, cancel)

    override fun search(
        query: String,
        limit: Int,
    ): List<SourceInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = http.getJson<GhSearch>("$api/search/repositories?q=${urlencode(q)}&per_page=${limit.coerceIn(1, MAX_PER_PAGE)}", true)
        return found.items.map { it.toInfo() }
    }

    override fun webUrl(id: String): String? {
        val repo = runCatching { validateRepo("GitHub repository", id) }.getOrNull() ?: return null
        val host = if (api.startsWith("https://api.")) "https://" + api.removePrefix("https://api.") else api.removeSuffix("/api/v3")
        return "$host/$repo"
    }

    override fun supportsDrafts() = true
}
