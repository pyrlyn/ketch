// Read-only commands about one package: `info`, `releases`, `changelog`, `search` and `why` from src/cmd/query.rs.
package ketch.cmd

import cli.Matches
import ketch.core.changelog.Origin
import ketch.core.changelog.findFile
import ketch.core.changelog.fromFile
import ketch.core.changelog.published
import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.info.Info
import ketch.core.info.gather
import ketch.core.info.resolve
import ketch.core.manifest.Resolver
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageSpec
import ketch.core.model.Provenance
import ketch.core.model.RetainedVersion
import ketch.core.model.SourceInfo
import ketch.core.model.TrustResult
import ketch.core.model.VersionSpec
import ketch.core.platform.host
import ketch.core.resolve.AssetTraceView
import ketch.core.resolve.ResolutionTrace
import ketch.core.resolve.ScoredAsset
import ketch.core.resolve.explain
import ketch.core.resolve.scoreAssets
import ketch.core.source.SourceRegistry
import ketch.core.state.State
import ketch.core.text.bytes
import ketch.core.text.truncate
import ketch.ui.bold
import ketch.ui.ctx
import ketch.ui.debug
import ketch.ui.dim
import ketch.ui.out
import ketch.ui.printable
import ketch.ui.step
import ketch.ui.table
import ketch.ui.warn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import ketch.core.changelog.Entry as ChangelogEntry
import ketch.core.releases.Entry as ReleaseEntry
import ketch.core.releases.list as listPublished

/** How many releases `info --releases` shows; `ketch releases -n` is the way to ask for more. */
private const val DEFAULT_RELEASES = 20

/** How wide a description may run in `search`. */
private const val SEARCH_DESCRIPTION = 60

/** The column a label pads to in `info` and `why`; it pads the painted label, escapes and all, as Rust's `{:<12}` does. */
private const val LABEL_WIDTH = 12

fun info(
    cfg: Config,
    args: Matches,
) {
    val state = State.load(cfg)
    val found = gather(ctx(cfg), state, args.pkg())
    found.origin?.let { debug("manifest from ${describeOrigin(it)}") }
    val listed =
        if (args.flag("releases")) {
            try {
                listReleases(cfg, found.manifest, found.installed, false, args.flag("draft"), DEFAULT_RELEASES)
            } catch (e: KetchError) {
                warn("${found.manifest.name}: ${e.message}")
                null
            }
        } else {
            null
        }
    val scored =
        found.latest
            ?.takeIf { args.flag("assets") }
            ?.let { scoreAssets(cfg, host(), it, found.manifest.asset) }
            .orEmpty()
    if (args.flag("json")) {
        printJson(infoJson(found, state, listed, scored))
    } else {
        printInfo(found, state, listed)
        if (args.flag("assets")) printAssets(scored)
    }
}

private fun infoJson(
    found: Info,
    state: State,
    listed: List<ReleaseEntry>?,
    scored: List<ScoredAsset>,
): JsonObject {
    val manifest = found.manifest
    val installed = found.installed
    val described = found.described
    val release = found.latest
    val local = manifest.source.scheme == "local"
    return jsonOf(
        "name" to JsonPrimitive(printable(manifest.name)),
        "source" to JsonPrimitive(manifest.source.toString()),
        "url" to JsonPrimitive(found.url),
        "description" to JsonPrimitive(jsonProse(found.description())),
        "homepage" to JsonPrimitive(jsonProse(found.homepage())),
        "stars" to JsonPrimitive(described?.stars),
        "license" to JsonPrimitive(jsonProse(described?.license)),
        "archived" to JsonPrimitive(described?.archived == true),
        "latest" to JsonPrimitive(release?.version?.toString()),
        "latest_tag" to JsonPrimitive(release?.tag?.let(::printable)),
        "installed" to JsonPrimitive(installed?.version?.toString()),
        "pinned" to JsonPrimitive(installed?.pinned == true),
        "channel" to JsonPrimitive(installed?.channel?.asStr),
        "publisher_trust" to JsonPrimitive(installed?.publisherTrust()),
        "provenance" to (installed?.provenance?.let { sortedJson(PRETTY.encodeToJsonElement(Provenance.serializer(), it)) } ?: JsonNull),
        "retained" to JsonArray(installed?.retained.orEmpty().map(::retainedJson)),
        "retention" to jsonOf("keep" to JsonPrimitive(state.retention.keep.toLong())),
        "local_kind" to JsonPrimitive(installed?.localKind?.asStr ?: "local".takeIf { local }),
        "local_path" to JsonPrimitive(installed?.localPath?.toString() ?: manifest.source.id.takeIf { local }),
        "releases" to (listed?.let(::releasesJson) ?: JsonNull),
        "assets" to JsonArray(scored.map(::assetJson)),
    )
}

private fun retainedJson(retained: RetainedVersion) =
    jsonOf(
        "version" to JsonPrimitive(retained.version.toString()),
        "prefix" to JsonPrimitive(retained.prefix.toString()),
        "sha256" to JsonPrimitive(retained.sha256),
        "trust" to sortedJson(PRETTY.encodeToJsonElement(serializer<TrustResult>(), retained.trust)),
    )

private fun assetJson(scored: ScoredAsset) =
    jsonOf(
        "name" to JsonPrimitive(printable(scored.asset.name)),
        "size" to JsonPrimitive(scored.asset.size),
        "score" to JsonPrimitive(scored.score.score),
        "reason" to JsonPrimitive(printable(scored.score.reason)),
        "emulated" to JsonPrimitive(scored.score.emulated),
    )

/** One `info` line. The value may be a registry's or a source's text, so it is filtered on its way to the terminal. */
private fun field(
    label: String,
    value: String,
) = out("${dim(label).padEnd(LABEL_WIDTH)} ${sanitize(value)}")

private fun printInfo(
    found: Info,
    state: State,
    listed: List<ReleaseEntry>?,
) {
    val manifest = found.manifest
    out(bold(manifest.name))
    // A registry or user manifest's prose: somebody else's text on its way to a terminal.
    found.description()?.let { out(sanitize(it)) }
    out("")
    field("source", manifest.source.toString())
    found.url?.let { field("url", it) }
    found.homepage()?.let { field("homepage", it) }
    found.described?.let(::printDescribed)
    found.latest?.let { field("latest", "${it.version} (${it.assets.size} assets)") }
    val pkg = found.installed
    if (pkg != null) {
        printInstalled(pkg, state)
    } else {
        field("installed", "no")
        if (manifest.source.scheme == "local") field("local path", manifest.source.id)
    }
    listed?.let {
        out("")
        printReleases(it)
    }
}

private fun printDescribed(described: SourceInfo) {
    described.stars?.let { field("stars", it.toString()) }
    described.license?.let { field("license", it) }
    if (described.archived) field("archived", "yes — this repository is no longer maintained")
}

private fun printInstalled(
    pkg: InstalledPackage,
    state: State,
) {
    field("installed", "${pkg.version}${if (pkg.pinned) " (pinned)" else ""}")
    if (pkg.channel != Channel.Stable) field("channel", pkg.channel.asStr)
    field("prefix", pkg.prefix.toString())
    field("verified", pkg.publisherTrust())
    pkg.provenance?.let { p ->
        val log = p.logIndex?.let { ", Rekor log index $it" }.orEmpty()
        field("signature", "${p.signature} ${p.verifier.asStr} by ${p.identity}$log")
    }
    pkg.localKind?.let { field("local kind", it.asStr) }
    pkg.localPath?.let { field("local path", it.toString()) }
    for (link in pkg.binaries()) field("binary", link.link.toString())
    field("retained", if (pkg.retained.isEmpty()) "none" else pkg.retained.joinToString(", ") { it.version.toString() })
    val keep = state.retention.keep
    field("retention", "keep $keep previous version${if (keep == 1u) "" else "s"}")
}

private fun printAssets(scored: List<ScoredAsset>) {
    out("")
    if (scored.isEmpty()) {
        out("no asset in this release can run on this machine")
    } else {
        table(
            listOf("asset", "size", "score", "why"),
            scored.map { listOf(it.asset.name, bytes(it.asset.size), it.score.score.toString(), it.score.reason) },
        )
    }
}

private fun listReleases(
    cfg: Config,
    manifest: Manifest,
    installed: InstalledPackage?,
    prerelease: Boolean,
    draft: Boolean,
    limit: Int,
): List<ReleaseEntry> {
    val cx = ctx(cfg)
    return listPublished(cx, SourceRegistry.load(cx), manifest, installed, prerelease, draft, limit)
}

/** `ketch releases <PKG>`: what the package's source has published, newest first, with the installed release marked. */
fun releases(
    cfg: Config,
    args: Matches,
) {
    val state = State.load(cfg)
    val (manifest, origin, installed) = resolve(ctx(cfg), state, args.pkg())
    origin?.let { debug("manifest from ${describeOrigin(it)}") }
    val entries = listReleases(cfg, manifest, installed, args.flag("prerelease"), args.flag("draft"), args.count("limit"))
    if (args.flag("json")) printJson(releasesJson(entries)) else printReleases(entries)
}

/** One renderer for `releases` and `info --releases`. */
private fun printReleases(entries: List<ReleaseEntry>) {
    if (entries.isEmpty()) {
        out("no releases")
        return
    }
    val marker = installedMarker()
    val cells =
        entries.map {
            listOf(
                if (it.installed) marker else "",
                it.tag,
                // A tag that is not a version has no version to show. The tag column already shows the raw spelling;
                // the version is the semver ketch compares, so `v10.4.2` reads `10.4.2`.
                it.version.semver ?: "-",
                it.date() ?: "-",
                it.kind.label,
            )
        }
    table(listOf("", "tag", "version", "published", "kind"), cells)
}

private fun releasesJson(entries: List<ReleaseEntry>) =
    JsonArray(
        entries.map {
            jsonOf(
                "tag" to JsonPrimitive(printable(it.tag)),
                "version" to JsonPrimitive(it.version.semver),
                "published_at" to JsonPrimitive(jsonProse(it.publishedAt)),
                "kind" to JsonPrimitive(it.kind.label),
                "installed" to JsonPrimitive(it.installed),
            )
        },
    )

/** A changelog to print, under the package and version it answers for. */
private data class Shown(
    val entry: ChangelogEntry,
    val name: String,
    val version: String,
)

/**
 * Print what changed in a release.
 *
 * The file the package ships is preferred over the notes the release published: it needs no network, and it is the
 * history of the version actually on disk. A file with no section for that version is not an answer about it, though —
 * plenty of projects cut a release before the heading is written — so that falls through to the notes, and back to the
 * whole file only when there are none to fall through to.
 */
fun changelog(
    cfg: Config,
    args: Matches,
) {
    val query = args.pkg()
    val spec = PackageSpec.parse(query)
    val installed = State.load(cfg).findSpec(spec)
    val file = args.flag("file")
    // Only the installed version has a file; any other release is the source's to answer for. `--file` always reads
    // the payload on disk when installed.
    val elsewhere = !file && (args.flag("latest") || spec.version is VersionSpec.Exact)
    val onDisk = installed?.takeIf { !args.flag("release") && !elsewhere }?.let { shipped(it, spec, file) }
    if (onDisk != null && (onDisk.entry.heading != null || file)) return show(onDisk)
    if (file) throw noFile(installed, query)
    val notes =
        try {
            published(ctx(cfg), spec, installed, args.flag("latest"))
        } catch (e: KetchError) {
            if (onDisk == null) throw e
            warn(e.message)
            null
        }
    show(notes?.let { Shown(it.entry, it.name, it.version) } ?: checkNotNull(onDisk))
}

private fun noFile(
    installed: InstalledPackage?,
    query: String,
) = KetchError.Msg(
    installed?.let { "${it.name} ships no changelog file; `ketch changelog ${it.name} --release` reads the published notes" }
        ?: "$query is not installed, so there is no file to read",
)

/** The changelog [pkg] ships, read for the version [spec] names or the installed one; null when it ships none. */
private fun shipped(
    pkg: InstalledPackage,
    spec: PackageSpec,
    file: Boolean,
): Shown? {
    val version = (spec.version as? VersionSpec.Exact)?.version ?: pkg.version.toString()
    val path = findFile(pkg.prefix)
    if (path == null) {
        debug("no changelog under ${pkg.prefix}")
        return null
    }
    val entry = fromFile(path, version)
    if (entry.heading == null && !file) debug("$path has no entry for $version")
    return Shown(entry, pkg.name, version)
}

/**
 * The changelog itself goes to stdout; where it came from goes to stderr, so `ketch changelog rg > NOTES.md` leaves
 * nothing but the markdown.
 */
private fun show(shown: Shown) {
    val (entry, name, version) = shown
    when (val origin = entry.origin) {
        is Origin.File -> {
            step("changelog", "$name $version · ${origin.path}")
            // Saying nothing here would pass a whole file off as one release.
            if (entry.heading == null) warn("no entry for $version in ${origin.path}; showing the whole file")
        }

        Origin.Release -> {
            step("changelog", "$name $version · release notes")
        }
    }
    entry.heading?.let {
        out(bold(it))
        out("")
    }
    out(if (entry.body.isEmpty()) dim("(nothing recorded)") else entry.body)
}

fun search(
    cfg: Config,
    args: Matches,
) {
    val query = args.values("query").joinToString(" ").trim()
    if (query.isEmpty()) throw KetchError.Msg("nothing to search for")
    val limit = args.count("limit")
    // Curated manifests first: they install with better names and known binaries, so they are the answer whenever one
    // matches.
    val known = Resolver.load(ctx(cfg)).search(query)
    if (known.isNotEmpty()) {
        out(bold("known packages"))
        table(
            listOf("package", "source", "description"),
            known.take(limit).map { listOf(it.name, it.source.toString(), truncate(it.description.orEmpty(), SEARCH_DESCRIPTION)) },
        )
        out("")
    }
    val rest = limit - minOf(known.size, limit)
    if (rest > 0) searchSources(cfg, query, rest, known.isEmpty())
}

private fun searchSources(
    cfg: Config,
    query: String,
    rest: Int,
    nothingKnown: Boolean,
) {
    var unreachable = 0
    val rows =
        SourceRegistry
            .load(ctx(cfg))
            .all
            .flatMap { source ->
                try {
                    source.search(query, rest).map { hit ->
                        listOf("${source.scheme()}:${hit.id}", hit.stars?.toString().orEmpty(), truncate(hit.description.orEmpty(), SEARCH_DESCRIPTION))
                    }
                } catch (e: KetchError) {
                    warn("${source.scheme()}: ${e.message}")
                    unreachable += 1
                    emptyList()
                }
            }.take(rest)
    when {
        rows.isNotEmpty() -> {
            out(bold("repositories"))
            table(listOf("package", "stars", "description"), rows)
        }

        !nothingKnown -> {}

        // Saying "no results" after every source failed would be a claim about the world made from no evidence at all
        // — the mistake `outdated` and `upgrade` already refuse to make.
        unreachable > 0 -> {
            throw KetchError.Msg(
                "no source could be searched for `$query` ($unreachable failed); nothing was found, and nothing was ruled out either",
            )
        }

        else -> {
            out("no results for `$query`")
        }
    }
}

/** A manifest origin, for a debug line. */
internal fun describeOrigin(origin: ManifestOrigin) =
    when (origin) {
        ManifestOrigin.Builtin -> "the built-in registry"
        is ManifestOrigin.Registry -> origin.path.toString()
        is ManifestOrigin.User -> origin.path.toString()
        ManifestOrigin.Inferred -> "inference"
    }

/**
 * Explain how a package would be resolved, without installing it.
 *
 * Uses the same resolver install does. Prints the trace even when no candidate exists, then fails so scripts can tell a
 * missing asset from a successful explanation.
 */
fun why(
    cfg: Config,
    args: Matches,
) {
    val spec = PackageSpec.parse(args.pkg())
    val trace = explain(ctx(cfg), SourceRegistry.load(ctx(cfg)), spec)
    if (args.flag("json")) out(PRETTY.encodeToString(ResolutionTrace.serializer(), trace)) else printWhy(trace)
    val selected = trace.version.selected
    when {
        trace.candidate != null -> Unit
        selected == null -> throw KetchError.NoRelease(spec.label())
        else -> throw KetchError.NoCompatibleAsset(trace.manifest.source, selected.tag, trace.target)
    }
}

/** One `why` line. Every value is the resolver's own text or already filtered, as Rust prints it. */
private fun whyField(
    label: String,
    value: String,
) = out("${dim(label).padEnd(LABEL_WIDTH)} $value")

private fun printWhy(trace: ResolutionTrace) {
    whyField("package", trace.`package`)
    whyField("target", trace.target)
    whyField("manifest", "${trace.manifest.tier}  ${trace.manifest.origin}")
    if (trace.manifest.matched != trace.manifest.name) whyField("matched", trace.manifest.matched)
    whyField("source", "${trace.source.scheme}:${trace.source.id}")
    val version = trace.version
    whyField(
        "version",
        version.selected?.let { "${version.request}  ${it.tag} (${it.version})${if (it.prerelease) "  prerelease" else ""}" }
            ?: "${version.request}  (no release)",
    )
    whyField("prerelease", if (version.includePrerelease) "included" else "excluded")
    printTraceAssets(trace.assets)
    out("")
    whyField("checksum", "${trace.checksum.policy}${if (trace.checksum.require) "  require" else ""}")
    whyField("trust", "advisory  strip_quarantine=${trace.trust.stripQuarantine}  allow_emulation=${trace.trust.allowEmulation}")
    whyField("candidate", trace.candidate?.let { "${it.name}  ${it.reason}" } ?: "(none)")
}

private fun printTraceAssets(assets: AssetTraceView) {
    if (assets.scored.isNotEmpty()) {
        out("")
        out(bold("assets"))
        table(
            listOf("score", "name", "reason", ""),
            assets.scored.map { listOf(it.score.toString(), it.name, it.reason, if (it.emulated) "emulated" else "") },
        )
    }
    if (assets.rejected.isNotEmpty()) {
        out("")
        out(bold("rejected"))
        table(listOf("name", "reason"), assets.rejected.map { listOf(it.name, it.reason) })
    }
}
