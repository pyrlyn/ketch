package ketch.core.trust

import ketch.core.error.KetchError
import ketch.core.model.Provenance
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.TrustMode
import ketch.core.model.TrustPolicy
import ketch.core.report.Report
import ketch.core.source.Source
import kotlinx.io.files.Path

/**
 * Hold a downloaded asset to the manifest's `trust` table.
 *
 * Null when there is no policy, or when a `warn` policy could not be satisfied — the install then records itself as
 * checksum-only. A `require` policy that cannot be satisfied is an error whatever the reason. Fail closed.
 */
// Everything but `report` is evidence the verifiers (T14) read; they take the same arguments Rust's do.
@Suppress("LongParameterList", "UnusedParameter")
fun verify(
    policy: TrustPolicy?,
    source: Source,
    release: Release,
    asset: ReleaseAsset,
    download: Path,
    sha256: String,
    staging: Path,
    report: Report,
): Provenance? {
    policy ?: return null
    // Until the verifiers arrive (T14) no policy can be satisfied, as in Rust ketch built without `trust`.
    return refuse(policy, asset.name, "this ketch was built without signature verification", report)
}

/** A policy that could not be satisfied: an error, or a warning when the manifest asked for one, said on [report]. */
fun refuse(
    policy: TrustPolicy,
    what: String,
    reason: String,
    report: Report,
): Provenance? =
    when (policy.mode) {
        TrustMode.Require -> {
            throw KetchError.Msg("$what: the ${policy.verifier} signature the manifest requires could not be verified: $reason")
        }

        TrustMode.Warn -> {
            report.warn("$what: ${policy.verifier} signature not verified ($reason); installing on its checksum alone")
            null
        }
    }
