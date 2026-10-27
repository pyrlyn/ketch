package ketch.core.wizard

import ketch.core.error.KetchError
import ketch.core.model.AssetSelector
import ketch.core.model.BinSpec
import ketch.core.model.ExtraKind
import ketch.core.model.ExtraPath
import ketch.core.model.Manifest
import ketch.core.model.PackageKind
import ketch.core.model.PackageRef
import ketch.core.model.normalizeName
import ketch.core.model.notAReference
import ketch.core.text.asciiLowercase
import ketch.core.text.rustTrim
import ketch.core.tomlfile.stringArrayLiteral
import ketch.core.tomlfile.stringLiteral

/*
 * The `ketch config create` questionnaire: answers in, a package file out. Kept apart from the command because the
 * mapping from answers to a manifest is business logic with rules worth testing, while the command body is only
 * prompting, previewing and writing. Everything here works on plain data, so the rules run with no terminal.
 */

/** The deepest `strip_prefix` `Manifest.validate` accepts. */
private const val MAX_STRIP = 8uL

/**
 * The answers the questionnaire collected, before they become a manifest. Plain data on purpose: the command fills it
 * field by field, and [manifest] is the only place the answers are interpreted.
 */
data class Answers(
    /** `owner/repo` or `scheme:id`. Null until answered: there is no default to invent. */
    val source: String? = null,
    /** The install name as typed. The default comes from the current directory; [manifest] normalizes it the way repo names are normalized. */
    val name: String = "",
    val description: String? = null,
    val homepage: String? = null,
    /** What the payload is; `Auto` means "look inside". */
    val kind: PackageKind = PackageKind.Auto,
    val prerelease: Boolean = false,
    /** Leading wrapper directories to drop. Null means "leave it to ketch"; an answer of `0` means the same and is stored as null. */
    val stripPrefix: ULong? = null,
    val provides: List<String> = emptyList(),
    val notes: String? = null,
    val bin: List<BinSpec> = emptyList(),
    val extraPaths: List<String> = emptyList(),
    val assetInclude: List<String> = emptyList(),
    val assetExclude: List<String> = emptyList(),
    /** Per-target asset overrides, keyed by `<os>-<arch>`. */
    val assetTarget: Map<String, String> = emptyMap(),
)

/**
 * Turn [answers] into the manifest they describe. The `source` answer goes back through `PackageRef`'s own parser, so
 * the questionnaire accepts exactly what every other command accepts. `Manifest.validate` runs last and its errors
 * propagate: a name or path that cannot be used verbatim is refused here, not when installing.
 */
fun manifest(answers: Answers): Manifest {
    val source = answeredSource(answers.source)
    val name = normalizeName(answers.name)
    if (name.isEmpty()) throw KetchError.Msg("`name` was never answered, and a manifest installs under its name")
    val manifest =
        Manifest(
            name = name,
            source = source,
            description = text(answers.description),
            homepage = text(answers.homepage),
            kind = answers.kind,
            asset = AssetSelector(answers.assetInclude, answers.assetExclude, answers.assetTarget),
            bin = answers.bin,
            stripPrefix = answers.stripPrefix,
            prerelease = answers.prerelease,
            provides = answers.provides,
            notes = text(answers.notes),
            extraPaths = answers.extraPaths.map(ExtraPath::Plain),
        )
    return manifest.also { requireNamedBin(it) }.also { it.validate() }
}

/** The `source` answer, read as every other command reads a package reference. */
private fun answeredSource(answer: String?): PackageRef {
    val sourceText =
        answer?.rustTrim()?.takeIf { it.isNotEmpty() }
            ?: throw KetchError.Msg("`source` was never answered, and it has no default")
    return PackageRef.parse(sourceText) ?: throw KetchError.Msg(notAReference(sourceText))
}

/**
 * A config written from now on says which binary is the command rather than leaving it to discovery, whose order
 * differs between platforms. Only here: `Manifest.validate` does not ask, so every manifest written before the rule
 * still loads.
 */
private fun requireNamedBin(manifest: Manifest) {
    if (manifest.kind != PackageKind.App && manifest.bin.none { it.name != null }) {
        throw KetchError.Msg("name the binary this package puts on PATH: a `bin` entry with a `name`")
    }
}

/**
 * Render [manifest] as the `ketch.toml` the project keeps and the registry receives. Field order follows
 * `docs/MANIFESTS.md`, and every empty or default field is omitted so the file says only what someone chose to say.
 */
fun render(manifest: Manifest): String = renderWithHeader(manifest, "# Written by `ketch config create`. Schema: docs/MANIFESTS.md.\n")

/** [render] under another first line, for a file another command writes (`ketch import` marks its files as its own to rewrite). */
fun renderWithHeader(
    manifest: Manifest,
    header: String,
): String =
    buildString {
        append(header)
        appendScalars(manifest)
        // A blank line before the list-shaped fields keeps the scalar block and the list block readable as two
        // groups, the way the documented example is laid out.
        if (manifest.bin.isNotEmpty() || manifest.extraPaths.isNotEmpty()) append('\n')
        if (manifest.bin.isNotEmpty()) append("bin = [${manifest.bin.joinToString(", ", transform = ::binEntry)}]\n")
        if (manifest.extraPaths.isNotEmpty()) append("extra_paths = ${extraPathsToml(manifest.extraPaths)}\n")
        appendAsset(manifest.asset)
    }

private fun StringBuilder.appendScalars(manifest: Manifest) {
    append("name = ${stringLiteral(manifest.name)}\n")
    append("source = ${stringLiteral(manifest.source.toString())}\n")
    manifest.description?.let { append("description = ${stringLiteral(it)}\n") }
    manifest.homepage?.let { append("homepage = ${stringLiteral(it)}\n") }
    val kind =
        when (manifest.kind) {
            PackageKind.Binary -> "binary"
            PackageKind.App -> "app"
            PackageKind.Auto -> null
        }
    kind?.let { append("kind = ${stringLiteral(it)}\n") }
    if (manifest.prerelease) append("prerelease = true\n")
    manifest.stripPrefix?.let { append("strip_prefix = $it\n") }
    if (manifest.provides.isNotEmpty()) append("provides = ${stringArrayLiteral(manifest.provides)}\n")
    manifest.notes?.let { append("notes = ${stringLiteral(it)}\n") }
}

private fun StringBuilder.appendAsset(asset: AssetSelector) {
    if (asset.include.isNotEmpty() || asset.exclude.isNotEmpty()) {
        append("\n[asset]\n")
        if (asset.include.isNotEmpty()) append("include = ${stringArrayLiteral(asset.include)}\n")
        if (asset.exclude.isNotEmpty()) append("exclude = ${stringArrayLiteral(asset.exclude)}\n")
    }
    if (asset.target.isNotEmpty()) {
        append("\n[asset.target]\n")
        // In key order, as Rust's `BTreeMap` writes it.
        asset.target
            .toList()
            .sortedBy { it.first }
            .forEach { (target, glob) -> append("${stringLiteral(target)} = ${stringLiteral(glob)}\n") }
    }
}

/** Parse a `kind` answer. Case and surrounding space are forgiven; anything else is not a kind and the question is asked again. */
fun parseKind(text: String): PackageKind? =
    when (text.rustTrim().asciiLowercase()) {
        "auto" -> PackageKind.Auto
        "binary" -> PackageKind.Binary
        "app" -> PackageKind.App
        else -> null
    }

/**
 * Parse a `strip_prefix` answer: a whole number from 0 to 8, where 0 means "leave it to ketch" and is stored as null.
 * The cap is the one `Manifest.validate` enforces, so the answer that reaches a manifest has already passed it.
 */
fun parseStripPrefix(text: String): ULong? {
    val trimmed = text.rustTrim()
    val levels = trimmed.toULongOrNull() ?: throw KetchError.Msg("`$trimmed` is not a number of directories")
    if (levels > MAX_STRIP) throw KetchError.Msg("`strip_prefix` must be at most 8")
    return levels.takeIf { it > 0uL }
}

/** Split a comma-separated answer into its parts, trimmed, with blanks gone: `rg, rgr ,,` is two aliases. */
fun splitList(text: String): List<String> = text.split(',').map { it.rustTrim() }.filter { it.isNotEmpty() }

private fun extraPathsToml(items: List<ExtraPath>): String =
    items.joinToString(", ", "[", "]") { entry ->
        when (entry) {
            is ExtraPath.Plain -> {
                stringLiteral(entry.relPath)
            }

            is ExtraPath.Spec -> {
                val spec = entry.spec
                val kind =
                    when (spec.kind) {
                        ExtraKind.Man -> "man"
                        ExtraKind.Completion -> "completion"
                    }
                val parts =
                    listOfNotNull(
                        "path = ${stringLiteral(spec.path)}",
                        "kind = ${stringLiteral(kind)}",
                        spec.shell?.let { "shell = ${stringLiteral(it.asStr)}" },
                        spec.section?.let { "section = ${stringLiteral(it)}" },
                    )
                "{ ${parts.joinToString(", ")} }"
            }
        }
    }

/** One `bin` entry as an inline table, `path` first to match the documented examples. */
private fun binEntry(spec: BinSpec): String {
    val parts = listOfNotNull(spec.path?.let { "path = ${stringLiteral(it)}" }, spec.name?.let { "name = ${stringLiteral(it)}" })
    // `Manifest.validate` refuses an entry with neither before a render could reach it; empty keeps the output
    // well-formed if that ever changes.
    return if (parts.isEmpty()) "" else "{ ${parts.joinToString(", ")} }"
}

/** An optional answer: trimmed, and whitespace-only counts as absent. */
private fun text(answer: String?): String? = answer?.rustTrim()?.takeIf { it.isNotEmpty() }
