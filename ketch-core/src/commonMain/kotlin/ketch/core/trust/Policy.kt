package ketch.core.trust

import ketch.core.config.validateRepo
import ketch.core.error.KetchError
import ketch.core.model.TrustPolicy
import ketch.core.model.Verifier
import ketch.core.text.rustTrim

private val APPLIES =
    mapOf(
        Verifier.Sigstore to setOf("issuer", "identity", "repository"),
        Verifier.Minisign to setOf("public_key"),
        Verifier.Gpg to setOf("public_key", "fingerprint"),
    )

private const val HTTPS = "https://"

/**
 * Check what deserialization cannot about a `trust` table. Called from `Manifest.validate`, so a registry entry
 * with a broken policy is refused before anyone tries to install it.
 */
fun checkPolicy(policy: TrustPolicy) {
    val present =
        mapOf(
            "issuer" to policy.issuer,
            "identity" to policy.identity,
            "repository" to policy.repository,
            "public_key" to policy.publicKey,
            "fingerprint" to policy.fingerprint,
        )
    // A key the verifier ignores would read as a check and be none.
    present.entries.firstOrNull { (key, value) -> value != null && key !in APPLIES.getValue(policy.verifier) }?.let { (key, _) ->
        throw KetchError.Msg("`trust.$key` does not apply to the ${policy.verifier} verifier")
    }
    // Parsing a minisign or gpg key takes that verifier's own code, which comes with signature checks (T14). A key
    // left unparsed here still installs nothing: verification cannot satisfy the policy.
    if (policy.verifier == Verifier.Sigstore) sigstorePolicy(policy)
}

private fun sigstorePolicy(policy: TrustPolicy) {
    val issuer = policy.issuer.orEmpty()
    val identity = policy.identity
    val refusal =
        when {
            issuer.length <= HTTPS.length || !issuer.startsWith(HTTPS) -> {
                "the sigstore verifier needs `trust.issuer`, the https URL of the OIDC issuer — " +
                    "https://token.actions.githubusercontent.com for GitHub Actions"
            }

            identity != null && (identity.isEmpty() || identity.rustTrim() != identity) -> {
                "`trust.identity` must be the certificate identity exactly, with no surrounding space"
            }

            policy.repository != null -> {
                validateRepo("trust.repository", policy.repository).takeIf { it != policy.repository }?.let {
                    "write `trust.repository` as `$it`"
                }
            }

            identity == null -> {
                "the sigstore verifier needs `trust.identity`, `trust.repository`, or both: " +
                    "an issuer alone admits anyone it issues certificates to"
            }

            else -> {
                null
            }
        }
    if (refusal != null) throw KetchError.Msg(refusal)
}
