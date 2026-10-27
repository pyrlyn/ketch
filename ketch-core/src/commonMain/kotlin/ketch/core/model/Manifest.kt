@file:OptIn(ExperimentalSerializationApi::class)

package ketch.core.model

import ketch.core.config.sanitizeComponent
import ketch.core.error.KetchError
import ketch.core.extra.classify
import ketch.core.extract.safeMemberPath
import ketch.core.text.asciiLowercase
import ketch.core.text.escapeDebug
import ketch.core.text.isRustWhitespace
import ketch.core.text.rustTrim
import ketch.core.trust.checkPolicy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How many wrapper directories a manifest may ask to strip. */
private const val MAX_STRIP_PREFIX = 8uL

/**
 * How to install one package. Unknown keys are refused on purpose: a manifest is hand-written, often by someone
 * else, and a misspelt key that is silently ignored installs the wrong thing with no complaint anywhere.
 */
@Serializable
data class Manifest(
    /** The install name: a directory in the store and the key in `state.json`. */
    val name: String,
    /** Where releases come from. */
    val source: PackageRef,
    /** One line, shown by `ketch info` and `ketch search`. */
    val description: String? = null,
    /** A URL for humans. */
    val homepage: String? = null,
    val kind: PackageKind = PackageKind.Auto,
    /** Narrows which release asset is chosen. */
    val asset: AssetSelector = AssetSelector(),
    /** Which executables to link, and under what names. Empty means discover. */
    val bin: List<BinSpec> = emptyList(),
    /** Leading path components to drop when extracting. Unsigned, as Rust's `usize`: `-1` is a parse error. */
    @SerialName("strip_prefix") val stripPrefix: ULong? = null,
    /** Consider prereleases when resolving `latest`. */
    val prerelease: Boolean = false,
    /** Alternate names this package answers to. */
    val provides: List<String> = emptyList(),
    /** Printed after a successful install. */
    val notes: String? = null,
    /** Man pages and completions to expose from the payload. */
    @SerialName("extra_paths") val extraPaths: List<ExtraPath> = emptyList(),
    /** Whose signature a release must carry. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trust: TrustPolicy? = null,
    /** Commands to run around install, update and uninstall. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hooks: Hooks = Hooks(),
) {
    /**
     * Check what deserialization cannot: that the names in this manifest are usable. `name` becomes a directory
     * in the store and each `bin.name` a link in the bin directory, so this is the trust boundary between a
     * manifest ketch did not write and the user's disk. Names that would need sanitizing are refused rather than
     * rewritten: a package that installs somewhere other than where it says is worse than one that refuses.
     */
    fun validate() {
        usableFileName("package name", name)
        bin.forEach(::validateBin)
        for (path in extraPaths) {
            containedPath("extra path", path.relPath, path.relPath)
            classify(path)
        }
        // Each level costs a directory listing of the payload, and no real archive nests this deep.
        if (stripPrefix != null && stripPrefix > MAX_STRIP_PREFIX) {
            throw KetchError.Msg("`strip_prefix` must be at most $MAX_STRIP_PREFIX")
        }
        provides.firstOrNull { alias -> alias.rustTrim().isEmpty() || alias.any { it.isRustWhitespace() } }?.let { alias ->
            throw KetchError.Msg("`$alias` cannot be an alias: it is not something anyone can type")
        }
        trust?.let(::validateTrust)
        validateHooks()
    }

    // A blank hook would still spawn a shell and report success; a key present and saying nothing is a mistake.
    private fun validateHooks() {
        hooks.entries().firstOrNull { (_, cmd) -> cmd != null && cmd.rustTrim().isEmpty() }?.let { (key, _) ->
            throw KetchError.Msg("`hooks.$key` is empty")
        }
    }

    private fun validateBin(spec: BinSpec) {
        spec.name?.let { usableFileName("binary name", it) }
        // A binary path is a glob: `*` and `?` are wildcards here, not characters Windows forbids in a name.
        spec.path?.let { containedPath("binary path", it, it.replace('*', '_').replace('?', '_')) }
        if (spec.name == null && spec.path == null) throw KetchError.Msg("a `bin` entry needs `name`, `path`, or both")
    }

    private fun validateTrust(trust: TrustPolicy) {
        // Both name files downloaded beside the asset. `{file}` is the only placeholder, and the sidecar cannot be
        // the signed file.
        trust.signature?.let { template ->
            val sample = template.replace("{file}", "x")
            if ('{' in sample || '}' in sample || sample == "x") {
                throw KetchError.Msg(
                    "`trust.signature` `${escapeDebug(template)}` must name a file beside the signed one, " +
                        "with `{file}` for that file's name",
                )
            }
            usableFileName("trust.signature", sample)
        }
        trust.signed?.let { usableFileName("trust.signed", it) }
        checkPolicy(trust)
    }

    companion object {
        /**
         * The manifest ketch uses when nobody wrote one: everything inferred. The name is sanitized rather than
         * validated: nobody authored it, so a reference whose last segment is unusable gets a usable name instead
         * of failing an install the user had every right to expect to work.
         */
        fun inferred(source: PackageRef) = Manifest(name = sanitizeComponent(normalizeName(source.shortName)), source = source)
    }
}

/**
 * Reject a name that could not be used verbatim as one path component. `sanitizeComponent` already knows every
 * character that is unsafe here, so asking whether it would change the value is the whole check.
 */
internal fun usableFileName(
    what: String,
    value: String,
) {
    if (sanitizeComponent(value) != value) throw KetchError.Msg("$what `${escapeDebug(value)}` is not usable as a file name")
}

/** Reject a path that would reach outside the payload it is relative to. [value] is shown; [checked] is tested. */
private fun containedPath(
    what: String,
    value: String,
    checked: String,
) {
    try {
        safeMemberPath(checked)
    } catch (e: KetchError) {
        throw KetchError.Msg("$what `$value` must stay inside the package", e)
    }
}

/** Lowercase a package name and strip decoration people put in repo names. */
fun normalizeName(raw: String): String {
    val lower = raw.rustTrim().asciiLowercase()
    return when {
        lower.endsWith(".rs") -> lower.removeSuffix(".rs")
        else -> lower.removeSuffix(".git")
    }
}
