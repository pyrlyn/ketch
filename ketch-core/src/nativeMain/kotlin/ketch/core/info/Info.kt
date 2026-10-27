// What `ketch info` knows about one package, gathered once for every front end: the manifest, the installed record,
// what the source says about the project and its newest release.
//
// Separate from the command so the CLI and the app answer the same question the same way; each only formats the
// answer. Nothing here is filtered for a terminal: the prose in it is a client app's, and every caller sanitises what
// it shows.
package ketch.core.info

import ketch.core.config.Config
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.Release
import ketch.core.model.SourceInfo

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
