// The install pipeline.
//
// resolve manifest → resolve release → score and pick an asset → download → verify checksum → extract → verify trust
// → place → record state.
//
// Every step is expressed against the `Source`, `Platform` and `Extractor` interfaces, so this package holds no
// GitHub-specific and no macOS-specific code.
package ketch.core.install

import fs.TempDir
import ketch.core.cancel.Cancel
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.LocalKind
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageSpec
import ketch.core.model.Provenance
import ketch.core.model.Release
import ketch.core.model.TrustResult
import ketch.core.model.Version
import kotlinx.io.files.Path
import kotlin.time.TimeSource

/** One install, fully specified. */
data class InstallRequest(
    val spec: PackageSpec,
    /** Reinstall even when the resolved version is already present. */
    val force: Boolean = false,
    val prerelease: Boolean = false,
    /** `--draft`: consider draft releases too, and prereleases with them. Refused for a source that has no drafts. */
    val draft: Boolean = false,
    /** Create links in the bin dir (and copy `.app`s) after unpacking. */
    val link: Boolean = true,
    /** Fail rather than record a first-seen hash when no checksum is published. */
    val requireChecksum: Boolean = false,
    /** Exact asset file name, bypassing scoring. */
    val assetOverride: String? = null,
    /** SHA-256 this asset is already known to have, from a lockfile. Checked before anything is unpacked. */
    val expectedSha256: String? = null,
    /**
     * When the build a lockfile recorded under a floating tag was published. A tag that now names a build published
     * at another time is refused before anything is downloaded.
     */
    val expectedPublished: String? = null,
    /** Override the resolved package name (used by `ketch install --path --name`). */
    val nameOverride: String? = null,
    /** `--bin`: which of several binaries sharing the package's name to link, answered before anyone asks. */
    val bin: String? = null,
    /** The choice `ketch.lock` recorded, consulted after state's: a fresh machine has no state, and no terminal to ask on during `ketch sync`. */
    val lockedBin: String? = null,
    /**
     * `ketch install` without `--yes`: an installed package with a newer release stops with
     * [ketch.core.error.KetchError.UpdateAvailable], so the command can ask before it updates.
     */
    val offerUpdate: Boolean = false,
    /** Install even when the package was installed against a published checksum and this release publishes none. */
    val allowUnverified: Boolean = false,
    /**
     * Stops this install at its next check: before the download, between chunks, and before anything is placed. A
     * copy shares the token, so a host keeps one to cancel with.
     */
    val cancel: Cancel = Cancel(),
)

/** What an install actually did. */
data class Installed(
    val pkg: InstalledPackage,
    /** The version that was replaced, when this was an upgrade or reinstall. */
    val replaced: Version?,
)

/**
 * Everything an install downloads, checks and unpacks, before it touches the install tree.
 *
 * Split out from [commit] so a batch can do this part for several packages at once. Nothing here writes outside the
 * cache, so two of these running side by side cannot collide: the store, the bin directory and `state.json` are only
 * reached from [commit], which stays sequential. Closing it deletes the unpacked payload; [commit] does that itself.
 */
class Prepared internal constructor(
    /**
     * Progress row id. [prepare] stages with [PackageSpec.label]; [commit] must use the same string, not the
     * manifest's name, or an alias or path install leaves a second row spinning on Installing.
     */
    internal val label: String,
    internal val manifest: Manifest,
    internal val origin: ManifestOrigin,
    internal val release: Release,
    internal val assetName: String,
    internal val sha256: String,
    internal val checksumVerified: Boolean,
    internal val trust: TrustResult,
    /** The publisher signature the manifest's `trust` table asked for. */
    internal val provenance: Provenance?,
    /** Carried from the request: [commit] is the only place that links. */
    internal val link: Boolean,
    /** Carried from the request: [commit] is where a binary is chosen. */
    internal val bin: String?,
    internal val lockedBin: String?,
    /** Root of the unpacked payload, inside [unpack]. */
    internal val payload: Path,
    private val unpack: TempDir,
    /** When this install began, so the statistic [commit] records covers the download and the unpack too. */
    internal val started: TimeSource.Monotonic.ValueTimeMark,
    /** Set for `local:` installs so list and info can show how the path was used. */
    internal val localKind: LocalKind?,
    internal val localPath: Path?,
    internal val cancel: Cancel,
    /** Decided from the request's `--pre` and the release, recorded by [commit]. */
    internal val channel: Channel,
) : AutoCloseable {
    override fun close() = unpack.close()
}
