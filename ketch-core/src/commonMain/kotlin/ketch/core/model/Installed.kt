@file:OptIn(ExperimentalSerializationApi::class)

package ketch.core.model

import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.rustTrim
import kotlinx.io.files.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where a manifest came from, written externally tagged as Rust's serde does: `"builtin"` or `{ "user": path }`. */
@Serializable(with = ManifestOriginSerializer::class)
sealed interface ManifestOrigin {
    /** Precedence name: user, registry, builtin, inferred. */
    val tier: String

    /** A path the user can open, or a short label when there is none. */
    fun location(): String = tier

    /** Shipped inside the ketch binary. */
    data object Builtin : ManifestOrigin {
        override val tier = "builtin"
    }

    /** A package folder in the fetched registry. */
    data class Registry(
        val path: Path,
    ) : ManifestOrigin {
        override val tier = "registry"

        override fun location() = path.toString()
    }

    /** A `.toml` in the user's manifest directory. */
    data class User(
        val path: Path,
    ) : ManifestOrigin {
        override val tier = "user"

        override fun location() = path.toString()
    }

    /** Nobody wrote one; ketch guessed. */
    data object Inferred : ManifestOrigin {
        override val tier = "inferred"
    }
}

/** How a `local:` package arrived on disk. */
@Serializable
enum class LocalKind(
    val asStr: String,
) {
    /** A recognised archive that was extracted into the store. */
    @SerialName("archive")
    Archive("archive"),

    /** A single executable file placed into the store and linked into bin. */
    @SerialName("binary")
    Binary("binary"),

    /** A symlink; payload bytes come from the resolved target. */
    @SerialName("symlink")
    Symlink("symlink"),

    /** A macOS `.app` bundle copied into the store and applications dir. */
    @SerialName("app")
    App("app"),
    ;

    override fun toString() = asStr
}

/** How an installed file is exposed outside the store. */
@Serializable
enum class LinkKind {
    /** Symlink in the bin dir pointing into the store. */
    @SerialName("symlink")
    Symlink,

    /**
     * A regular file copied into the bin dir. Windows cannot create unprivileged symlinks, so identity is the
     * file's own bytes against the store copy.
     */
    @SerialName("copied_file")
    CopiedFile,

    /** A `.app` copied out to the applications dir; removing it is a delete. */
    @SerialName("copied_app")
    CopiedApp,

    /** A `.app` symlinked into the applications dir. */
    @SerialName("linked_app")
    LinkedApp,
}

/** What a recorded link is for. Mechanism stays in [LinkKind]; this tells a PATH entry from a man page. */
@Serializable
enum class LinkRole {
    /** A binary on PATH; the default, so state written before this field existed reads as binaries. */
    @SerialName("binary")
    Binary,

    @SerialName("man")
    Man,

    @SerialName("completion")
    Completion,
}

/** A link ketch created for a package, recorded so removal can undo exactly it. */
@Serializable
@JsonIgnoreUnknownKeys
data class LinkRecord(
    /** The path we created and are responsible for removing. */
    @Serializable(with = PathSerializer::class) val link: Path,
    /** What it points at inside the store. */
    @Serializable(with = PathSerializer::class) val target: Path,
    val kind: LinkKind,
    /** Absent on state written before man and completion links existed. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val role: LinkRole = LinkRole.Binary,
)

/** One installed package as recorded in `state.json`: version, source, asset, checksum and links. */
@Serializable
@JsonIgnoreUnknownKeys
data class InstalledPackage(
    val name: String,
    val version: Version,
    val source: PackageRef,
    val tag: String,
    val target: TargetSpec,
    @SerialName("asset_name") val assetName: String,
    /** SHA-256 of the downloaded asset, always recorded. */
    val sha256: String,
    /** True when the checksum was published by the source rather than trusted on first use. */
    @SerialName("checksum_verified") val checksumVerified: Boolean = false,
    @SerialName("installed_at") val installedAt: Long,
    /** Store directory holding the extracted payload. */
    @Serializable(with = PathSerializer::class) val prefix: Path,
    val links: List<LinkRecord> = emptyList(),
    val pinned: Boolean = false,
    val origin: ManifestOrigin,
    /** Kept so `upgrade` reuses the same selection rules as `install`. */
    val manifest: Manifest? = null,
    /** Present when `source` is `local:…`. */
    @SerialName("local_kind") @EncodeDefault(EncodeDefault.Mode.NEVER) val localKind: LocalKind? = null,
    /** Absolute path the user named, for `info --json`. Usually mirrors the id. */
    @SerialName("local_path") @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = PathSerializer::class)
    val localPath: Path? = null,
    /** Signature check recorded when this version was placed. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trust: TrustResult = TrustResult.NotApplicable,
    /** Older prefixes still on disk. Upgrade appends; `ketch prune` removes. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val retained: List<RetainedVersion> = emptyList(),
    /** The publisher signature verified when this version was placed, from the manifest's `trust` table. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val provenance: Provenance? = null,
    /**
     * The binary picked when a release shipped several sharing the package's name and no manifest named one.
     * Reused on upgrade, reinstall, relink and rollback rather than asking again.
     */
    @SerialName("bin_choice") @EncodeDefault(EncodeDefault.Mode.NEVER) val binChoice: String? = null,
    /** The release stream this package follows. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val channel: Channel = Channel.Stable,
    /** The installed release was a draft, whose tag can later name the published release: then an upgrade. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val draft: Boolean = false,
    /** When the installed build was published, for a package on `dev`: how a rebuilt tag is told apart. */
    @SerialName("published_at") @EncodeDefault(EncodeDefault.Mode.NEVER) val publishedAt: String? = null,
) {
    /** How far the publisher of this download is established. */
    fun publisherTrust(): String =
        when {
            provenance != null -> "signed"
            checksumVerified -> "checksum"
            else -> "first use"
        }

    fun binaries(): List<LinkRecord> = links.filter { it.role == LinkRole.Binary && (it.kind == LinkKind.Symlink || it.kind == LinkKind.CopiedFile) }

    /** Most recently replaced version, if any is still recorded. */
    fun previousRetained(): RetainedVersion? = retained.firstOrNull()

    /** A retained version by version string or tag, with its index. */
    fun findRetained(spec: String): IndexedValue<RetainedVersion>? = retained.withIndex().firstOrNull { (_, r) -> r.version.matchesRequest(spec) || r.tag.eqIgnoreAsciiCase(spec.rustTrim()) }
}

/**
 * How many previous prefixes `ketch prune` leaves on disk. Upgrade never deletes a prefix; this number is applied
 * only by the explicit prune command, so a keep of 1 still leaves every version until someone prunes.
 */
@Serializable
@JsonIgnoreUnknownKeys
data class RetentionPolicy(
    val keep: UInt = 1u,
)

/** A previously installed version whose prefix is still in the store. */
@Serializable
@JsonIgnoreUnknownKeys
data class RetainedVersion(
    val version: Version,
    @Serializable(with = PathSerializer::class) val prefix: Path,
    /** SHA-256 of the asset that produced this prefix. */
    val sha256: String,
    @SerialName("checksum_verified") val checksumVerified: Boolean = false,
    val links: List<LinkRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trust: TrustResult = TrustResult.NotApplicable,
    /** Carried so a rollback restores what was verified for this version. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val provenance: Provenance? = null,
    val tag: String,
    @SerialName("asset_name") val assetName: String,
    @SerialName("installed_at") val installedAt: Long = 0,
    val target: TargetSpec,
) {
    companion object {
        /** Snapshot the version-specific fields of an installed package. */
        fun fromInstalled(pkg: InstalledPackage) =
            RetainedVersion(
                version = pkg.version,
                prefix = pkg.prefix,
                sha256 = pkg.sha256,
                checksumVerified = pkg.checksumVerified,
                links = pkg.links,
                trust = pkg.trust,
                provenance = pkg.provenance,
                tag = pkg.tag,
                assetName = pkg.assetName,
                installedAt = pkg.installedAt,
                target = pkg.target,
            )
    }
}

/** Persisted form of a platform trust check, externally tagged. `NotApplicable` is the default for old state. */
@Serializable(with = TrustResultSerializer::class)
sealed interface TrustResult {
    data class Trusted(
        val authority: String,
    ) : TrustResult {
        override fun toString() = "trusted ($authority)"
    }

    data class Weak(
        val detail: String,
    ) : TrustResult {
        override fun toString() = "weak ($detail)"
    }

    data class Untrusted(
        val detail: String,
    ) : TrustResult {
        override fun toString() = "untrusted ($detail)"
    }

    data object NotApplicable : TrustResult {
        override fun toString() = "not applicable"
    }
}

/** Serde's externally tagged enum: a unit variant is its name, any other a one-key object. */
private fun tagged(
    variant: String,
    value: JsonElement?,
): JsonElement = if (value == null) JsonPrimitive(variant) else JsonObject(mapOf(variant to value))

private fun untag(
    element: JsonElement,
    what: String,
): Pair<String, JsonElement?> =
    when {
        element is JsonPrimitive && element.isString -> element.content to null
        element is JsonObject && element.size == 1 -> element.entries.single().toPair()
        else -> throw SerializationException("invalid $what")
    }

private fun json(decoder: Decoder) = decoder as? JsonDecoder ?: throw SerializationException("ketch reads its records only as JSON")

private fun json(encoder: Encoder) = encoder as? JsonEncoder ?: throw SerializationException("ketch writes its records only as JSON")

internal object ManifestOriginSerializer : KSerializer<ManifestOrigin> {
    override val descriptor = SerialDescriptor("ketch.ManifestOrigin", JsonElement.serializer().descriptor)

    override fun serialize(
        encoder: Encoder,
        value: ManifestOrigin,
    ) {
        val path =
            when (value) {
                is ManifestOrigin.Registry -> value.path
                is ManifestOrigin.User -> value.path
                else -> null
            }
        json(encoder).encodeJsonElement(tagged(value.tier, path?.let { JsonPrimitive(it.toString()) }))
    }

    override fun deserialize(decoder: Decoder): ManifestOrigin {
        val (variant, value) = untag(json(decoder).decodeJsonElement(), "manifest origin")
        val path = value?.let { Path(it.jsonPrimitive.content) }
        return when {
            variant == "builtin" && path == null -> ManifestOrigin.Builtin
            variant == "inferred" && path == null -> ManifestOrigin.Inferred
            variant == "registry" && path != null -> ManifestOrigin.Registry(path)
            variant == "user" && path != null -> ManifestOrigin.User(path)
            else -> throw SerializationException("unknown variant `$variant`, expected one of `builtin`, `registry`, `user`, `inferred`")
        }
    }
}

internal object TrustResultSerializer : KSerializer<TrustResult> {
    override val descriptor = SerialDescriptor("ketch.TrustResult", JsonElement.serializer().descriptor)

    override fun serialize(
        encoder: Encoder,
        value: TrustResult,
    ) {
        val element =
            when (value) {
                is TrustResult.Trusted -> tagged("trusted", buildJsonObject { put("authority", value.authority) })
                is TrustResult.Weak -> tagged("weak", buildJsonObject { put("detail", value.detail) })
                is TrustResult.Untrusted -> tagged("untrusted", buildJsonObject { put("detail", value.detail) })
                TrustResult.NotApplicable -> tagged("not_applicable", null)
            }
        json(encoder).encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): TrustResult {
        val (variant, value) = untag(json(decoder).decodeJsonElement(), "trust result")
        val field = { key: String ->
            (value as? JsonObject)?.get(key)?.jsonPrimitive?.content ?: throw SerializationException("missing field `$key`")
        }
        return when (variant) {
            "trusted" -> TrustResult.Trusted(field("authority"))
            "weak" -> TrustResult.Weak(field("detail"))
            "untrusted" -> TrustResult.Untrusted(field("detail"))
            "not_applicable" -> TrustResult.NotApplicable
            else -> throw SerializationException("unknown variant `$variant`")
        }
    }
}
