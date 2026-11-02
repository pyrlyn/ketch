@file:OptIn(ExperimentalSerializationApi::class)

package ketch.core.model

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
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a package's payload is installed as. */
@Serializable
enum class PackageKind {
    /** Decide from what the payload actually contains. */
    @SerialName("auto")
    Auto,

    /** Command-line executables linked into the bin dir. */
    @SerialName("binary")
    Binary,

    /** A macOS `.app` bundle placed in the applications dir. */
    @SerialName("app")
    App,
}

/** Which release asset to pick. Empty means "let the platform decide". */
@Serializable
@JsonIgnoreUnknownKeys
data class AssetSelector(
    /** Asset must match at least one of these (glob: `*` and `?`). */
    val include: List<String> = emptyList(),
    /** Asset must match none of these. */
    val exclude: List<String> = emptyList(),
    /** Per-target override, keyed by the target's display form: `"macos-aarch64" = "*-aarch64-apple-darwin.tar.gz"`. */
    @Serializable(with = SortedStringMapSerializer::class) val target: Map<String, String> = emptyMap(),
) {
    fun isEmpty() = include.isEmpty() && exclude.isEmpty() && target.isEmpty()
}

/** One executable to expose on PATH. */
@Serializable
@JsonIgnoreUnknownKeys
data class BinSpec(
    /**
     * Path inside the extracted payload. Globs allowed; when a glob matches several files, the one whose stem is
     * `name` is linked. When absent, ketch discovers executables.
     */
    val path: String? = null,
    /** Name of the link. Defaults to the file name of `path`. */
    val name: String? = null,
)

/** What an `extra_paths` entry is for: required in the table form, inferred from path rules for a bare string. */
@Serializable
enum class ExtraKind {
    /** A man page, linked into the user man root. */
    @SerialName("man")
    Man,

    /** A shell completion script, linked into that shell's user directory. */
    @SerialName("completion")
    Completion,
}

/** Shell whose user completion directory an extra path lands in. */
@Serializable
enum class CompletionShell(
    /** Stable name used in doctor checks and error text. */
    val asStr: String,
) {
    @SerialName("bash")
    Bash("bash"),

    @SerialName("zsh")
    Zsh("zsh"),

    @SerialName("fish")
    Fish("fish"),

    @SerialName("elvish")
    Elvish("elvish"),

    @SerialName("powershell")
    Powershell("powershell"),
}

/** Table form of `extra_paths`: an explicit kind, so path rules are not guessed. */
@Serializable
data class ExtraPathSpec(
    /** Path inside the extracted payload. Same containment rules as a string entry. */
    val path: String,
    val kind: ExtraKind,
    /** Required for a completion when the file name does not name a shell. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val shell: CompletionShell? = null,
    /** Man section (`1`, `8`, `1p`). Inferred from a `*.N` file name when absent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val section: String? = null,
)

/** One `extra_paths` entry: a payload-relative string, or a table with `kind`. Written as whichever it is. */
@Serializable(with = ExtraPathSerializer::class)
sealed interface ExtraPath {
    /** The payload-relative path, whether the entry was a string or a table. */
    val relPath: String

    /** Classified by the path rules in `docs/MANIFESTS.md`. */
    data class Plain(
        override val relPath: String,
    ) : ExtraPath

    /** Explicit kind; path rules are consulted only to fill `shell` or `section`. */
    data class Spec(
        val spec: ExtraPathSpec,
    ) : ExtraPath {
        override val relPath get() = spec.path
    }
}

internal object ExtraPathSerializer : KSerializer<ExtraPath> {
    override val descriptor = SerialDescriptor("ketch.ExtraPath", JsonElement.serializer().descriptor)

    override fun serialize(
        encoder: Encoder,
        value: ExtraPath,
    ) = when (value) {
        is ExtraPath.Plain -> encoder.encodeString(value.relPath)
        is ExtraPath.Spec -> encoder.encodeSerializableValue(ExtraPathSpec.serializer(), value.spec)
    }

    override fun deserialize(decoder: Decoder): ExtraPath {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ExtraPath reads only from JSON trees")
        val element = json.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString -> ExtraPath.Plain(element.content)
            element is JsonObject -> ExtraPath.Spec(json.json.decodeFromJsonElement(ExtraPathSpec.serializer(), element))
            else -> throw SerializationException("data did not match any variant of untagged enum ExtraPath")
        }
    }
}

/** An `extra_paths` entry after classification, before a destination is chosen. */
data class ClassifiedExtra(
    val relPath: String,
    val kind: ExtraKind,
    val shell: CompletionShell?,
    val section: String?,
)

/**
 * Commands a manifest runs around its own install, update and uninstall, each one line for the platform shell.
 * They run only from a manifest in the user's own manifest directory: anywhere else the manifest is someone
 * else's file, and a command in it is their code.
 */
@Serializable
data class Hooks(
    @SerialName("before_install") @EncodeDefault(EncodeDefault.Mode.NEVER) val beforeInstall: String? = null,
    @SerialName("after_install") @EncodeDefault(EncodeDefault.Mode.NEVER) val afterInstall: String? = null,
    @SerialName("before_update") @EncodeDefault(EncodeDefault.Mode.NEVER) val beforeUpdate: String? = null,
    @SerialName("after_update") @EncodeDefault(EncodeDefault.Mode.NEVER) val afterUpdate: String? = null,
    @SerialName("before_uninstall") @EncodeDefault(EncodeDefault.Mode.NEVER) val beforeUninstall: String? = null,
    @SerialName("after_uninstall") @EncodeDefault(EncodeDefault.Mode.NEVER) val afterUninstall: String? = null,
) {
    /** Every hook with its manifest key, set or not. */
    fun entries(): List<Pair<String, String?>> =
        listOf(
            "before_install" to beforeInstall,
            "after_install" to afterInstall,
            "before_update" to beforeUpdate,
            "after_update" to afterUpdate,
            "before_uninstall" to beforeUninstall,
            "after_uninstall" to afterUninstall,
        )

    /** True when no hook is set, so an empty table need not be written. */
    fun isEmpty() = entries().all { it.second == null }
}
