// Resolution trace shared by install and `ketch why`.
//
// Install and `ketch why` share these functions so an explanation cannot drift from what would actually be installed.
// Nothing here downloads or fetches checksum files. The one network call is the same `Source.resolve` install makes;
// `explain` shows a spinner on stderr while that call runs.
package ketch.core.resolve

import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.model.AssetSelector
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.globMatch
import ketch.core.platform.AssetScore
import ketch.core.platform.Platform
import ketch.core.platform.host
import ketch.core.report.Ctx
import ketch.core.source.ListOpts
import ketch.core.source.RejectedRelease
import ketch.core.source.Source
import ketch.core.text.BYTE_ORDER
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A release asset ranked for this platform. */
data class ScoredAsset(
    val asset: ReleaseAsset,
    val score: AssetScore,
)

/** An asset that scoring refused, and why. */
@Serializable
data class RejectedAsset(
    val name: String,
    val reason: String,
)

/** Ranked assets plus the ones that did not make the cut. */
data class AssetEvaluation(
    val scored: List<ScoredAsset>,
    val rejected: List<RejectedAsset>,
)

/**
 * Rank a release's assets for this platform, best first.
 *
 * Assets the platform or the manifest rejects are listed on `rejected` rather than dropped, so `ketch why` can show the
 * same decision install will make.
 */
fun evaluateAssets(
    cfg: Config,
    platform: Platform,
    release: Release,
    selector: AssetSelector,
): AssetEvaluation {
    val targetPattern = selector.target[cfg.target.toString()]
    val scored = mutableListOf<ScoredAsset>()
    val rejected = mutableListOf<RejectedAsset>()
    for (asset in release.assets) {
        when (val verdict = judge(cfg, platform, asset, selector, targetPattern)) {
            is Verdict.Keep -> scored += ScoredAsset(asset, verdict.score)
            is Verdict.Refuse -> rejected += RejectedAsset(asset.name, verdict.reason)
        }
    }
    // Name is the tie-break so repeated runs pick the same asset.
    scored.sortWith(compareByDescending<ScoredAsset> { it.score.score }.thenBy(BYTE_ORDER) { it.asset.name })
    rejected.sortWith(compareBy(BYTE_ORDER) { it.name })
    return AssetEvaluation(scored, rejected)
}

/** The asset's score, or the reason it was refused. */
private sealed interface Verdict {
    data class Keep(
        val score: AssetScore,
    ) : Verdict

    data class Refuse(
        val reason: String,
    ) : Verdict
}

private fun judge(
    cfg: Config,
    platform: Platform,
    asset: ReleaseAsset,
    selector: AssetSelector,
    targetPattern: String?,
): Verdict {
    val excluded = selector.exclude.firstOrNull { globMatch(it, asset.name) }
    return when {
        excluded != null -> {
            Verdict.Refuse("excluded by `$excluded`")
        }

        // A per-target pattern is the user naming the file outright, so it overrides the platform's opinion rather
        // than filtering it.
        targetPattern != null && globMatch(targetPattern, asset.name) -> {
            Verdict.Keep(AssetScore(Int.MAX_VALUE, cfg.target.arch, false, "manifest pins `$targetPattern` for ${cfg.target}"))
        }

        targetPattern != null -> {
            Verdict.Refuse("does not match the pinned pattern `$targetPattern` for ${cfg.target}")
        }

        selector.include.isNotEmpty() && selector.include.none { globMatch(it, asset.name) } -> {
            Verdict.Refuse("does not match any include pattern")
        }

        else -> {
            platform.scoreAsset(asset.name, cfg.allowEmulation)?.let(Verdict::Keep) ?: Verdict.Refuse("incompatible with ${cfg.target}")
        }
    }
}

/** Ranked assets only, the shape install and `info --assets` already use. */
fun scoreAssets(
    cfg: Config,
    platform: Platform,
    release: Release,
    selector: AssetSelector,
) = evaluateAssets(cfg, platform, release, selector).scored

/** How a checksum would be handled if this candidate were installed. */
@Serializable
data class ChecksumPolicy(
    /** `require_checksums` from config. Sidecar files are not fetched here. */
    val require: Boolean,
    /** Algorithm on the chosen asset, when the release already carried one. */
    @SerialName("asset_digest") val assetDigest: String?,
    /** What install would do with that information, without extra requests. */
    val policy: String,
)

/** Local trust checks run after download and never refuse the install. */
@Serializable
data class TrustPolicy(
    @SerialName("strip_quarantine") val stripQuarantine: Boolean,
    @SerialName("allow_emulation") val allowEmulation: Boolean,
    /** Platform trust is advisory: a failed check is reported, not fatal. */
    @SerialName("blocks_install") val blocksInstall: Boolean,
)

/** Where the manifest came from, without embedding untrusted prose. */
@Serializable
data class ManifestStep(
    val tier: String,
    val origin: String,
    val name: String,
    val source: String,
    /** The alias or reference the user typed, when it differs from `name`. */
    val matched: String,
)

/** The source that will answer for this package. */
@Serializable
data class SourceStep(
    val scheme: String,
    val id: String,
)

/** The release `Source.resolve` returned, plus how it was asked for. */
@Serializable
data class VersionStep(
    val request: String,
    @SerialName("include_prerelease") val includePrerelease: Boolean,
    val selected: SelectedRelease?,
    /**
     * Other listing entries, when a caller walked a listing through `pick`. Empty after `Source.resolve`: that call is
     * one request, like install.
     */
    val rejected: List<RejectedRelease>,
)

/** The release that won version selection. */
@Serializable
data class SelectedRelease(
    val tag: String,
    val version: String,
    val prerelease: Boolean,
)

/** The asset that would be installed. */
@Serializable
data class Candidate(
    val name: String,
    val score: Int,
    val reason: String,
    val emulated: Boolean,
)

/** A scored asset, stripped of URLs, headers and other untrusted control text. */
@Serializable
data class ScoredAssetView(
    val name: String,
    val score: Int,
    val reason: String,
    val emulated: Boolean,
)

/** Scored and rejected assets as they appear in `ketch why`. */
@Serializable
data class AssetTraceView(
    val scored: List<ScoredAssetView>,
    val rejected: List<RejectedAsset>,
)

/** End-to-end explanation of one package decision. */
@Serializable
data class ResolutionTrace(
    val `package`: String,
    val target: String,
    val manifest: ManifestStep,
    val source: SourceStep,
    val version: VersionStep,
    val assets: AssetTraceView,
    val checksum: ChecksumPolicy,
    val trust: TrustPolicy,
    val candidate: Candidate?,
)

/** The listing options install and `why` both pass into `Source.resolve`. */
fun listOpts(
    cfg: Config,
    manifest: Manifest,
    extraPrerelease: Boolean,
) = ListOpts(includePrerelease = extraPrerelease || cfg.prerelease || manifest.prerelease)

/**
 * Explain [manifest] the way install would resolve it from [source], without downloading. One `Source.resolve` call,
 * the same function install uses. Checksum sidecars and trust inspection are described from config and the already
 * fetched release, not performed.
 */
fun explainWith(
    cx: Ctx,
    source: Source,
    spec: PackageSpec,
    manifest: Manifest,
    origin: ManifestOrigin,
    platform: Platform = host(),
): ResolutionTrace {
    val cfg = cx.cfg
    // Same helper install uses. `why` has no `--prerelease` flag, so the extra bit is false; config and the manifest
    // still match a default install.
    val opts = listOpts(cfg, manifest, false)
    val selected =
        try {
            cx.report.activity("resolving ${manifest.name}").run { source.resolve(manifest.source.id, spec.version, opts) }
        } catch (_: KetchError.NoRelease) {
            null
        }
    val evaluation = selected?.let { evaluateAssets(cfg, platform, it, manifest.asset) } ?: AssetEvaluation(emptyList(), emptyList())
    val best = evaluation.scored.firstOrNull()
    return ResolutionTrace(
        `package` = safe(manifest.name),
        target = cfg.target.toString(),
        manifest = ManifestStep(origin.tier, safe(origin.location()), safe(manifest.name), safe(manifest.source.toString()), safe(spec.label())),
        source = SourceStep(safe(manifest.source.scheme), safe(manifest.source.id)),
        version =
            VersionStep(
                spec.version.toString(),
                opts.includePrerelease,
                selected?.let { SelectedRelease(safe(it.tag), it.version.toString(), it.prerelease) },
                emptyList(),
            ),
        assets =
            AssetTraceView(
                evaluation.scored.map { ScoredAssetView(safe(it.asset.name), it.score.score, safe(it.score.reason), it.score.emulated) },
                evaluation.rejected.map { RejectedAsset(safe(it.name), safe(it.reason)) },
            ),
        checksum = checksumPolicy(cfg, best?.asset),
        trust = TrustPolicy(cfg.stripQuarantine, cfg.allowEmulation, blocksInstall = false),
        candidate = best?.let { Candidate(safe(it.asset.name), it.score.score, safe(it.score.reason), it.score.emulated) },
    )
}

internal fun checksumPolicy(
    cfg: Config,
    asset: ReleaseAsset?,
): ChecksumPolicy {
    val digest = asset?.digest?.takeIf { it.algo.eqIgnoreAsciiCase("sha256") }?.let { safe(it.algo) }
    val policy =
        when {
            asset == null -> "not-applicable"
            digest != null -> "published-digest"
            cfg.requireChecksums -> "require"
            else -> "trust-on-first-use"
        }
    return ChecksumPolicy(cfg.requireChecksums, digest, policy)
}

internal fun safe(text: String) = sanitize(text)
