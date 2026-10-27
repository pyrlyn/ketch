package ketch.core.install

import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.PackageRef
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import ketch.core.report.Report
import ketch.core.source.ListOpts
import ketch.core.source.SourceRegistry

/** What an upgrade decision needs to know of an installed package, apart from the rest of its record, so `ketch list` can carry it for every row. */
data class InstalledBuild(
    val name: String,
    val version: Version,
    val tag: String,
    val channel: Channel,
    /** The installed release was a draft. */
    val draft: Boolean,
    val assetName: String,
    val sha256: String,
    val publishedAt: String?,
) {
    /** The tag a package on a floating tag keeps resolving; null for every other channel, which looks for the newest release instead. */
    fun followedTag(): String? = tag.takeIf { channel == Channel.Dev }

    companion object {
        fun of(pkg: InstalledPackage) = InstalledBuild(pkg.name, pkg.version, pkg.tag, pkg.channel, pkg.draft, pkg.assetName, pkg.sha256, pkg.publishedAt)
    }
}

/**
 * Whether [latest], found for [have] with [opts], should replace what is installed. The one rule behind
 * `ketch upgrade`, `ketch outdated` and `ketch list`.
 *
 * Versions are compared, not tags: a retagged release is not an upgrade, and neither is a source that briefly
 * reports an older one, or a prerelease [opts] did not ask for — GitHub's `latest` can be an unflagged `-rc` tag. A
 * draft is the exception: once published, the release under its tag is what the draft was a preview of. A draft that
 * is no longer listed was deleted or published under another tag; that is said, and is not an error. A package on a
 * floating tag is upgraded by a new build under that tag.
 */
fun isUpgrade(
    report: Report,
    have: InstalledBuild,
    latest: Release,
    opts: ListOpts,
): Boolean {
    val sameTag = latest.tag == have.tag
    val unasked = !opts.includePrerelease && (latest.prerelease || latest.version.isPrerelease)
    val upgrade =
        when {
            have.draft && sameTag -> !latest.draft
            have.channel == Channel.Dev && sameTag -> rebuilt(have, latest)
            else -> !sameTag && latest.version > have.version && !unasked
        }
    if (!upgrade && have.draft && !sameTag) {
        report.warn(
            "${have.name}: the draft release ${have.tag} it was installed from is gone; " +
                "it was deleted or published under another tag",
        )
    }
    return upgrade
}

/**
 * Whether [release], under the tag [pkg] was installed from, is another build than the one installed.
 *
 * The installed asset's digest decides when the source publishes one: it describes the bytes, which a republish
 * changes even when the date does not. Without it the publication date is all there is, and a date that is missing
 * on either side is no evidence of a new build.
 */
internal fun rebuilt(
    pkg: InstalledBuild,
    release: Release,
): Boolean {
    // Nightlies often stamp a date or commit into their file names, so a vanished asset is a new build rather than
    // an incomplete release.
    val asset = release.assets.firstOrNull { it.name == pkg.assetName } ?: return true
    val digest = asset.digest?.takeIf { it.algo == "sha256" }
    val installed = pkg.publishedAt
    val now = release.publishedAt
    return if (digest != null) !digest.hex.equals(pkg.sha256, ignoreCase = true) else installed != null && now != null && installed != now
}

/**
 * The request that moves installed [pkg] to the release tagged [tag], as `ketch upgrade` makes it: the exact tag that
 * was reported, so nothing can change between the plan a person approved and what is installed.
 */
fun upgradeRequest(
    cfg: Config,
    pkg: InstalledPackage,
    tag: String,
    bin: String?,
    cancel: Cancel,
) = InstallRequest(
    spec = PackageSpec("${pkg.source}@$tag", pkg.source, null, VersionSpec.Exact(tag)),
    force = true,
    // The tag is exact, so these only carry the channel forward: an upgrade that lands on a stable release must not
    // end prereleases, or drafts.
    prerelease = pkg.channel == Channel.Pre,
    draft = pkg.channel == Channel.Draft,
    // A package installed with --no-link stays unlinked.
    link = pkg.links.isNotEmpty(),
    requireChecksum = cfg.requireChecksums,
    // The installed name, which `--name` may have chosen. Resolving the source alone would infer another and
    // install a second copy.
    nameOverride = pkg.name,
    bin = bin,
    cancel = cancel,
)

/** The listing options [latestRelease] asks an installed package's source with; `ketch list` needs them apart from the lookup, to key its cache. */
fun installedListOpts(
    pkg: InstalledPackage,
    prerelease: Boolean,
): ListOpts {
    // A manifest that asks for prereleases got one at install time; asking for `latest` without it here would then
    // report the package as up to date forever, however many prereleases it has moved through since.
    val draft = pkg.channel == Channel.Draft
    return ListOpts(
        includePrerelease = prerelease || draft || pkg.channel == Channel.Pre || pkg.manifest?.prerelease == true,
        includeDraft = draft,
    )
}

/** Newest release available for an installed package, for `outdated` and `upgrade`. [draft] widens the lookup to drafts for this run, as [prerelease] does to prereleases. */
fun latestRelease(
    sources: SourceRegistry,
    pkg: InstalledPackage,
    prerelease: Boolean,
    draft: Boolean,
): Release = releaseOf(sources, pkg.source, InstalledBuild.of(pkg).followedTag(), upgradeOpts(pkg, prerelease, draft))

/** The options [latestRelease] looks with, which [isUpgrade] needs too: what the lookup allowed decides what may count as an upgrade. */
fun upgradeOpts(
    pkg: InstalledPackage,
    prerelease: Boolean,
    draft: Boolean,
): ListOpts =
    installedListOpts(pkg, prerelease).let {
        it.copy(includeDraft = it.includeDraft || draft, includePrerelease = it.includePrerelease || draft)
    }

/**
 * The release an installed package is compared against: the newest under [opts], or for a package on a floating tag
 * that tag again, since it has no successor to look for, only a newer build under the same name. Drafts are not asked
 * of a source that has none: across many packages, one without drafts is simply answered without them.
 */
fun releaseOf(
    sources: SourceRegistry,
    source: PackageRef,
    tag: String?,
    opts: ListOpts,
): Release {
    val found = sources.forRef(source)
    val version = tag?.let { VersionSpec.Exact(it) } ?: VersionSpec.Latest
    return found.resolve(source.id, version, opts.copy(includeDraft = opts.includeDraft && found.supportsDrafts()))
}
