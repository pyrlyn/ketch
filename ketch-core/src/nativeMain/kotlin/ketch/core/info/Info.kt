// What `ketch info` knows about one package, gathered once for every front end: the manifest, the installed record,
// what the source says about the project and its newest release.
//
// Separate from the command so the CLI and the app answer the same question the same way; each only formats the
// answer. Nothing here is filtered for a terminal: the prose in it is a client app's, and every caller sanitises what
// it shows.
package ketch.core.info

import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.manifest.Resolver
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.SourceInfo
import ketch.core.model.VersionSpec
import ketch.core.report.Ctx
import ketch.core.source.ListOpts
import ketch.core.source.SourceRegistry
import ketch.core.state.State

/** One package, as far as it could be found out. */
data class Info(
    val manifest: Manifest,
    /** Where the manifest came from; null when the registry no longer knows the package and the one recorded at install time was used. */
    val origin: ManifestOrigin?,
    val installed: InstalledPackage?,
    /** The project's page on its forge, when the source has one. */
    val url: String?,
    /** What the source says about the project. */
    val described: SourceInfo?,
    /** The newest release, by the package's prerelease rules. */
    val latest: Release?,
) {
    /** The manifest's description, or the source's when it has none. */
    fun description() = manifest.description ?: described?.description

    /** The manifest's homepage, or the source's when it has none. */
    fun homepage() = manifest.homepage ?: described?.homepage
}

/**
 * Whether a lookup for this package should see prereleases: [extra] (a `--pre`), `config.toml`, the manifest, or an
 * install on the `pre` channel. One rule for `info`, `ketch releases` and anything that lists beside them, so they never
 * disagree about what "the package's releases" are.
 */
fun wantsPrerelease(
    cfg: Config,
    manifest: Manifest,
    installed: InstalledPackage?,
    extra: Boolean,
) = extra ||
    cfg.prerelease ||
    manifest.prerelease ||
    // GitHub flags a nightly as a prerelease, so a dev install that did not count here would leave its own release out
    // of `ketch releases`.
    installed?.channel == Channel.Pre ||
    installed?.channel == Channel.Dev

/** What [resolve] finds: the manifest, where it came from, and the installed record the query matches. */
data class Resolved(
    val manifest: Manifest,
    val origin: ManifestOrigin?,
    val installed: InstalledPackage?,
)

/** The manifest [query] names and the installed record it matches, without asking any source anything: what `ketch releases` needs before it lists. */
fun resolve(
    cx: Ctx,
    state: State,
    query: String,
): Resolved {
    val installed = state.find(query)
    val (manifest, origin) = Resolver.load(cx).resolveOrRecorded(PackageSpec.parse(query), installed)
    return Resolved(manifest, origin, installed)
}

/**
 * Everything known about [query], a name or spec.
 *
 * An installed package always has an answer, even when the registry has forgotten the name it was installed under. A
 * source that cannot be reached costs only what it would have said: that is a warning on `cx.report`, not an error,
 * because the rest of the answer still stands.
 */
fun gather(
    cx: Ctx,
    state: State,
    query: String,
): Info {
    val (manifest, origin, installed) = resolve(cx, state, query)
    val source = warnOnFailure(cx, manifest) { SourceRegistry.load(cx).forRef(manifest.source) }
    val described =
        source?.let {
            try {
                it.describe(manifest.source.id)
            } catch (e: KetchError) {
                cx.report.debug("describe failed: ${e.message}")
                null
            }
        }
    val opts = ListOpts(includePrerelease = wantsPrerelease(cx.cfg, manifest, installed, extra = false))
    val latest = source?.let { warnOnFailure(cx, manifest) { it.resolve(manifest.source.id, VersionSpec.Latest, opts) } }
    return Info(manifest, origin, installed, source?.webUrl(manifest.source.id), described, latest)
}

private inline fun <T : Any> warnOnFailure(
    cx: Ctx,
    manifest: Manifest,
    body: () -> T,
): T? =
    try {
        body()
    } catch (e: KetchError) {
        cx.report.warn("${manifest.name}: ${e.message}")
        null
    }
