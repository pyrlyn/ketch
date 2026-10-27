// Where packages come from. A Source turns an opaque id into releases and downloadable assets. GitHub is built in;
// anything else can be added as an external plugin executable without recompiling ketch (see Plugin.kt and
// docs/PLUGINS.md).
package ketch.core.source

import ketch.core.cancel.Cancel
import ketch.core.error.KetchError
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.SourceInfo
import ketch.core.model.VersionSpec
import ketch.core.report.ProgressSink
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

private const val DEFAULT_LIMIT = 30

/** Knobs that apply to listing releases, independent of the source. */
data class ListOpts(
    val includePrerelease: Boolean = false,
    /** Keep draft releases. Only a source whose `supportsDrafts` is true has any; the rest drop them whatever this says. */
    val includeDraft: Boolean = false,
    /** Upper bound on releases fetched. Sources may return fewer. */
    val limit: Int = DEFAULT_LIMIT,
) {
    /**
     * These options widened for `--draft`: drafts, and prereleases with them, since a draft is often marked
     * prerelease too and asking without them would hide exactly the build that was asked for. A source without drafts
     * is refused by name rather than quietly answered with published releases; [scheme] names it.
     */
    fun askForDrafts(
        source: Source,
        scheme: String,
    ): ListOpts {
        if (!source.supportsDrafts()) {
            throw KetchError.Msg("$scheme: packages have no draft releases; --draft works for GitHub packages")
        }
        return copy(includeDraft = true, includePrerelease = true)
    }
}

/** A backend that can enumerate and fetch releases. ketch keeps one instance per scheme for the life of the process. */
interface Source {
    /** The scheme this source answers to, e.g. `github`. Must be stable: it appears in user input and in recorded state. */
    fun scheme(): String

    /** Repository-level metadata; null when the source has nothing beyond releases. */
    fun describe(id: String): SourceInfo? = null

    /**
     * Releases, newest first. Drafts are excluded unless the source supports them and `opts.includeDraft` is set;
     * prereleases are included only when `opts.includePrerelease` is set.
     */
    fun listReleases(
        id: String,
        opts: ListOpts,
    ): List<Release>

    /**
     * Resolve a version request to one release. The default walks [listReleases], which is correct for every source;
     * override only to use a cheaper endpoint (GitHub does, for `latest`).
     */
    fun resolve(
        id: String,
        want: VersionSpec,
        opts: ListOpts,
    ): Release {
        val widened = optsFor(want, opts)
        return pick(id, listReleases(id, widened), want, widened)
    }

    /**
     * Checksums published alongside a release, keyed by asset file name. [wanted] is the asset actually being
     * installed: a source that pays per file for this (GitHub publishes one sidecar per asset) looks that one up
     * first, so its own request limits can never be what leaves this install unverified.
     */
    fun checksums(
        id: String,
        release: Release,
        wanted: String,
    ): Map<String, String> = emptyMap()

    /** Download one asset to [dest], returning its SHA-256 as lowercase hex. Stops with [KetchError.Cancelled], leaving nothing at [dest], once [cancel] fires. */
    fun download(
        asset: ReleaseAsset,
        dest: Path,
        progress: ProgressSink,
        cancel: Cancel,
    ): String

    /** Free-text search. Sources that cannot search return an empty list. */
    fun search(
        query: String,
        limit: Int,
    ): List<SourceInfo> = emptyList()

    /** A browsable URL for humans, when one exists. */
    fun webUrl(id: String): String? = null

    /** Whether this source can list draft releases at all; a request for drafts from one that cannot is refused. */
    fun supportsDrafts(): Boolean = false
}

/**
 * Listing options widened for an exact request. Naming a tag is explicit consent to install that release, prerelease
 * or not. The consent has to be applied to the listing: sources drop prereleases before [pick] ever sees them, so
 * filtering afterwards means an exact request for a prerelease could never be satisfied at all.
 */
fun optsFor(
    want: VersionSpec,
    opts: ListOpts,
) = opts.copy(includePrerelease = opts.includePrerelease || want is VersionSpec.Exact)

/** A release that version selection refused, and why. */
@Serializable
data class RejectedRelease(
    val tag: String,
    val version: String,
    val reason: String,
)

/** Result of [selectRelease]: the winner plus every discarded listing entry. */
data class ReleaseSelection(
    val selected: Release,
    /** Filled for callers that explain a listing walk. [pick] discards it. */
    val rejected: List<RejectedRelease>,
)

/** Shared release-selection logic, so every source picks versions the same way. */
fun pick(
    id: String,
    releases: List<Release>,
    want: VersionSpec,
    opts: ListOpts,
) = selectRelease(id, releases, want, opts).selected

private fun Release.rejected(reason: String) = RejectedRelease(tag, version.toString(), reason)

/** Same selection as [pick], with every discarded release kept. */
fun selectRelease(
    id: String,
    releases: List<Release>,
    want: VersionSpec,
    opts: ListOpts,
): ReleaseSelection {
    val (drafts, considered) = releases.partition { it.draft && !opts.includeDraft }
    val rejected = drafts.mapTo(mutableListOf()) { it.rejected("draft") }
    return when (want) {
        is VersionSpec.Exact -> selectExact(id, want.version, considered, rejected)
        VersionSpec.Latest -> selectLatest(id, considered, opts, rejected)
    }
}

private fun Release.matches(tag: String) = this.tag.eqIgnoreAsciiCase(tag) || version.matchesRequest(tag)

private fun selectExact(
    id: String,
    tag: String,
    considered: List<Release>,
    rejected: MutableList<RejectedRelease>,
): ReleaseSelection {
    val at = considered.indexOfFirst { it.matches(tag) }
    if (at < 0) throw KetchError.NoRelease("$id@$tag")
    considered.forEachIndexed { i, release ->
        if (i != at) rejected += release.rejected(if (release.matches(tag)) "not the first exact match" else "does not match the requested tag")
    }
    return ReleaseSelection(considered[at], rejected)
}

private fun selectLatest(
    id: String,
    considered: List<Release>,
    opts: ListOpts,
    rejected: MutableList<RejectedRelease>,
): ReleaseSelection {
    val hasStable = considered.any { !it.prerelease && !it.version.isPrerelease }
    var pool = considered
    if (!opts.includePrerelease && hasStable) {
        val (pre, stable) = considered.partition { it.prerelease || it.version.isPrerelease }
        pre.forEach { rejected += it.rejected("prerelease") }
        pool = stable
    }
    // A tag that is not a version at all cannot be "the highest" next to ones that are: compared as text,
    // `desktop-v1.0.0` or `nightly` outranks `v2.0.0` on its first letter. One repository can release two products
    // (ketch's own tags the macOS app `desktop-v*`), and only the versioned tags are this package's.
    if (pool.any { it.version.isSemver }) {
        val (versioned, other) = pool.partition { it.version.isSemver }
        other.forEach { rejected += it.rejected("not a version") }
        pool = versioned
    }
    // Rust's `max_by` keeps the last of equal maxima.
    val selected = pool.reduceOrNull { best, r -> if (r.version >= best.version) r else best } ?: throw KetchError.NoRelease(id)
    pool.forEach { if (it.tag != selected.tag || it.version != selected.version) rejected += it.rejected("not the highest version") }
    return ReleaseSelection(selected, rejected)
}
