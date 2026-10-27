package ketch.core.install

import ketch.core.error.KetchError
import ketch.core.model.InstalledPackage
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.TrustResult
import ketch.core.platform.Platform
import ketch.core.platform.TrustVerdict
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.Source
import kotlinx.io.files.Path

private const val SHORT_HASH = 12

/** Whether [actual] was confirmed against a checksum the source publishes for [asset]. */
@Suppress("LongParameterList")
internal fun verifyChecksum(
    source: Source,
    id: String,
    release: Release,
    asset: ReleaseAsset,
    actual: String,
    require: Boolean,
    report: Report,
): Boolean {
    // The sidecars are only worth the extra requests when the asset carries no digest.
    val published = asset.digest?.hex ?: publishedChecksum(source, id, release, asset.name, report)
    return when {
        published == null && require -> {
            throw KetchError.ChecksumMissing(asset.name)
        }

        published == null -> {
            report.debug("${asset.name} publishes no checksum; recording ${actual.take(SHORT_HASH)} on first use")
            false
        }

        published.equals(actual, ignoreCase = true) -> {
            true
        }

        else -> {
            throw KetchError.ChecksumMismatch(asset.name, published, actual)
        }
    }
}

/**
 * The source's published checksum for [name]. An empty map is the only "publishes no checksum" answer; an error
 * means the lookup itself failed while a checksum may well exist, so the install goes on first-use and says so.
 */
private fun publishedChecksum(
    source: Source,
    id: String,
    release: Release,
    name: String,
    report: Report,
): String? =
    try {
        source.checksums(id, release, name)[name]
    } catch (e: KetchError) {
        report.warn("could not read published checksums for $name: ${e.message}; recording the downloaded hash on first use")
        null
    }

/**
 * Refuse to replace a package installed against a published checksum with a release that publishes none, unless the
 * request says to.
 *
 * Without this, a release whose checksums were removed — by its publisher or by whoever took over the repository —
 * installs with only the first-use note, and the one check that catches a replaced asset quietly disappears. A
 * lockfile hash is a stronger pin than either, so it stands in for consent.
 */
internal fun refuseChecksumDowngrade(
    req: InstallRequest,
    existing: InstalledPackage?,
    checksumVerified: Boolean,
    asset: ReleaseAsset,
) {
    val dropped = !checksumVerified && existing?.checksumVerified == true
    if (dropped && !req.allowUnverified && req.expectedSha256 == null) {
        throw KetchError.ChecksumDropped(existing.name, asset.name)
    }
}

/**
 * Refuse a floating tag that a lockfile pinned to one build and that now names another, before anything is
 * downloaded. A source that does not say when a release was published leaves nothing to compare.
 */
internal fun checkPublished(
    req: InstallRequest,
    name: String,
    release: Release,
) {
    val locked = req.expectedPublished ?: return
    val now = release.publishedAt ?: return
    if (locked != now) {
        throw KetchError.Msg(
            "$name: ${release.tag} now names another build (published $now, the lockfile recorded $locked); " +
                "run `ketch lock` again to accept it",
        )
    }
}

/**
 * Hold a payload to the hash a lockfile recorded for it.
 *
 * Checked apart from the source's own checksum: that one says the download was not corrupted, this one says the
 * release is still the one that was locked — and a release that changed under a tag it already published is exactly
 * what a lockfile exists to catch. Every payload path calls it before anything is placed.
 */
internal fun checkLocked(
    req: InstallRequest,
    name: String,
    asset: String,
    sha256: String,
) {
    val expected = req.expectedSha256 ?: return
    if (!expected.equals(sha256, ignoreCase = true)) {
        throw KetchError.Msg(
            "$name: $asset does not match the lockfile\n  locked $expected\n  got    $sha256\n" +
                "The release was replaced after the lock was written. Install it deliberately and re-run `ketch lock` " +
                "rather than accepting a payload nobody recorded.",
        )
    }
}

/**
 * Inspect the payload and strip quarantine only when the platform says the code is genuinely trusted. A failed check
 * never blocks an install the user explicitly asked for; it is reported instead.
 */
internal fun checkTrust(
    platform: Platform,
    cx: Ctx,
    payload: Path,
    name: String,
): TrustResult {
    val report = cx.report
    val verdict =
        try {
            platform.verifyTrust(payload)
        } catch (e: KetchError) {
            report.debug("trust check failed for $name: ${e.message}")
            return TrustResult.NotApplicable
        }
    when (verdict) {
        is TrustVerdict.Trusted -> report.debug("signed by ${verdict.authority}")
        is TrustVerdict.Weak -> report.debug("weak signature: ${verdict.detail}")
        is TrustVerdict.Untrusted -> report.debug("unsigned: ${verdict.detail}")
        TrustVerdict.NotApplicable -> Unit
    }
    if (cx.cfg.stripQuarantine && verdict.mayStripQuarantine()) {
        try {
            platform.clearQuarantine(payload)
        } catch (e: KetchError) {
            report.debug("could not clear quarantine: ${e.message}")
        }
    }
    return when (verdict) {
        is TrustVerdict.Trusted -> TrustResult.Trusted(verdict.authority)
        is TrustVerdict.Weak -> TrustResult.Weak(verdict.detail)
        is TrustVerdict.Untrusted -> TrustResult.Untrusted(verdict.detail)
        TrustVerdict.NotApplicable -> TrustResult.NotApplicable
    }
}
