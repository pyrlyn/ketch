package ketch.core.model

import ketch.core.config.currentRepo
import ketch.core.text.asciiLowercase
import ketch.core.text.isAsciiAlphanumeric
import ketch.core.text.rustTrim
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A fully qualified package location: which source, and an id that source understands. For GitHub the id is
 * `owner/repo`. Stored as the `scheme:id` string, so manifests and the state file read the way a user types it.
 */
@Serializable(with = PackageRefSerializer::class)
data class PackageRef(
    val scheme: String,
    val id: String,
) {
    /** Last path segment: the natural default package name. */
    val shortName: String get() = id.substringAfterLast('/')

    override fun toString() = "$scheme:$id"

    companion object {
        fun github(id: String) = PackageRef("github", id)

        /**
         * `scheme:id`, or a bare `owner/repo`, which means GitHub. A bare word with neither `:` nor `/` is not a
         * reference but an alias to look up in the registry, so it gives null rather than a guess.
         */
        fun parse(input: String): PackageRef? {
            val text = input.rustTrim()
            val colon = text.indexOf(':')
            // A scheme never contains `/`; that keeps `https://host/x` and `owner/repo` from reading as schemes.
            val scheme = if (colon > 0) text.substring(0, colon) else ""
            val rest = text.substring(colon + 1)
            val ref =
                when {
                    text.isEmpty() -> null

                    scheme.isNotEmpty() && scheme.all(::schemeChar) && rest.isNotEmpty() -> scheme(scheme, rest)

                    // Every stored reference is read through here, so a file written before a repository moved
                    // resolves under its new name.
                    '/' in text -> github(currentRepo(text))

                    else -> null
                }
            return ref
        }

        private fun scheme(
            scheme: String,
            rest: String,
        ): PackageRef {
            val lower = scheme.asciiLowercase()
            return if (lower == "github") github(currentRepo(rest)) else PackageRef(lower, rest)
        }

        private fun schemeChar(c: Char) = c.isAsciiAlphanumeric() || c == '-'
    }
}

internal object PackageRefSerializer : KSerializer<PackageRef> {
    override val descriptor = PrimitiveSerialDescriptor("ketch.PackageRef", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: PackageRef,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): PackageRef {
        val text = decoder.decodeString()
        return PackageRef.parse(text)
            ?: throw SerializationException("`$text` is not a package reference; expected `scheme:id` or `owner/repo`")
    }
}

/** Which version the user asked for. */
sealed interface VersionSpec {
    data object Latest : VersionSpec {
        override fun toString() = "latest"
    }

    /** An exact tag or version string, matched with and without a `v` prefix. */
    data class Exact(
        val version: String,
    ) : VersionSpec {
        override fun toString() = version
    }
}

/** Raw user input for a package: `ripgrep`, `BurntSushi/ripgrep@14.1.0`, `github:cli/cli`, `myplugin:some-id@2.0`. */
data class PackageSpec(
    val raw: String,
    /** Set when the input names a source explicitly or looks like `owner/repo`. */
    val reference: PackageRef?,
    /** Set when the input is a bare name to look up in the registry. */
    val alias: String?,
    val version: VersionSpec,
) {
    /** Best available human label before a manifest is resolved. */
    fun label(): String = alias ?: reference?.shortName ?: raw

    companion object {
        fun parse(input: String): PackageSpec {
            val raw = input.rustTrim()
            // The version splits off at an `@` after the final `/`, so scoped ids keep working and
            // `owner/repo@v1` splits correctly.
            val from = raw.lastIndexOf('/') + 1
            val at = raw.indexOf('@', from)
            val split = at > from
            val body = if (split) raw.substring(0, at) else raw
            val version = if (split) VersionSpec.Exact(raw.substring(at + 1)) else VersionSpec.Latest
            val reference = PackageRef.parse(body)
            return PackageSpec(raw, reference, if (reference == null) body.asciiLowercase() else null, version)
        }
    }
}
