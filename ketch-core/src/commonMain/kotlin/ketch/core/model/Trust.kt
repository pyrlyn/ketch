@file:OptIn(ExperimentalSerializationApi::class)

package ketch.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** Which kind of publisher signature a `trust` table asks for. */
@Serializable
enum class Verifier(
    val asStr: String,
    /** The sidecar name each tool writes by default. */
    internal val defaultSignature: String,
) {
    @SerialName("sigstore")
    Sigstore("sigstore", "{file}.sigstore.json"),

    @SerialName("minisign")
    Minisign("minisign", "{file}.minisig"),

    @SerialName("gpg")
    Gpg("gpg", "{file}.asc"),
    ;

    override fun toString() = asStr
}

/** What an unverifiable signature does to the install. */
@Serializable
enum class TrustMode {
    /** Refuse it. Fail closed. */
    @SerialName("require")
    Require,

    /** Install on the checksum alone, and say so. */
    @SerialName("warn")
    Warn,
}

/**
 * A manifest's `trust` table: whose signature a release must carry. Which keys a verifier reads is checked by
 * `ketch.core.trust.checkPolicy`, called from [Manifest.validate].
 */
@Serializable
data class TrustPolicy(
    val verifier: Verifier,
    val mode: TrustMode = TrustMode.Require,
    /** The sidecar's name; `{file}` is the signed file's name. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val signature: String? = null,
    /** A glob naming a checksum list the signature covers instead of the asset. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val signed: String? = null,
    /** sigstore: the OIDC issuer of the signing certificate. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val issuer: String? = null,
    /** sigstore: the certificate's exact identity. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val identity: String? = null,
    /** sigstore: any GitHub Actions workflow in this `owner/repo`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val repository: String? = null,
    /** minisign: the key. gpg: the armored key block. */
    @SerialName("public_key") @EncodeDefault(EncodeDefault.Mode.NEVER) val publicKey: String? = null,
    /** gpg: the primary key's full fingerprint. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fingerprint: String? = null,
) {
    /** The sidecar that signs [file]. */
    fun signatureName(file: String): String = (signature ?: verifier.defaultSignature).replace("{file}", file)
}

/**
 * A publisher signature that verified, as `state.json` records it. [identity] is what the manifest pinned, never
 * what the signature file says about itself: that text is the signer's own, and is not printed.
 */
@Serializable
@JsonIgnoreUnknownKeys
data class Provenance(
    val verifier: Verifier,
    val identity: String,
    /** The sidecar release asset and its SHA-256, so the check can be redone. */
    val signature: String,
    @SerialName("signature_sha256") val signatureSha256: String,
    /** The checksum list the signature covered, when it covered that and not the asset itself. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val signed: String? = null,
    /** sigstore: the Rekor transparency-log index of the entry. */
    @SerialName("log_index") @EncodeDefault(EncodeDefault.Mode.NEVER) val logIndex: Long? = null,
)
