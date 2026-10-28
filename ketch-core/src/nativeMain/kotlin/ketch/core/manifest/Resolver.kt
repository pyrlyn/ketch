// Turning what the user typed into a `Manifest` (Rust `manifest`). Four tiers, in order: a user manifest in
// `~/.ketch/manifests/<name>.toml`, the fetched package registry, the built-in registry compiled into the binary,
// then inference from the source reference itself. Inference is what lets `ketch install owner/repo` work for a
// repository nobody has curated.
package ketch.core.manifest

import fs.FsException
import fs.joinPath
import fs.listDir
import fs.readText
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageSpec
import ketch.core.model.normalizeName
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.text.asciiLowercase
import ketch.core.text.rustTrim
import kotlinx.io.files.Path

/** Resolves specs to manifests. Built once per command. */
class Resolver internal constructor(
    private val builtin: List<Manifest>,
    /** Packages from the fetched registry, paired with their `ketch.toml`. */
    private val registry: List<Pair<Manifest, Path>>,
    /** User manifests, paired with the file they came from so the origin points at something the user can edit. */
    private val user: List<Pair<Manifest, Path>>,
) {
    /**
     * Resolve a spec, or fall back to the manifest recorded when [installed] was installed, so a package the
     * registry has since dropped can still be described. The origin is null for the recorded one.
     */
    fun resolveOrRecorded(
        spec: PackageSpec,
        installed: InstalledPackage?,
    ): Pair<Manifest, ManifestOrigin?> =
        try {
            resolve(spec)
        } catch (e: KetchError) {
            if (installed == null) throw e
            (installed.manifest ?: Manifest.inferred(installed.source)) to null
        }

    /** Resolve a spec, reporting where the manifest came from. */
    fun resolve(spec: PackageSpec): Pair<Manifest, ManifestOrigin> {
        // An explicit reference still gets a curated manifest when one exists: `ketch install BurntSushi/ripgrep`
        // should link `rg`, not `ripgrep`.
        spec.reference?.let { reference ->
            return find { sameSource(it.source, reference) } ?: (Manifest.inferred(reference) to ManifestOrigin.Inferred)
        }
        val alias = spec.alias?.let(::normalizeName).orEmpty()
        return find { answersTo(it, alias) } ?: throw KetchError.Msg(
            "no package named `$alias`; run `ketch update` to refresh the registry, " +
                "`ketch search $alias` to look on GitHub, or give an `owner/repo` reference",
        )
    }

    /** Every alias the registry knows, for completion and `ketch search`. */
    fun aliases(): List<String> =
        manifests()
            .flatMap { sequenceOf(it.name) + it.provides }
            .distinct()
            .sorted()
            .toList()

    /** Known packages matching a free-text query, highest tier first. A name is listed once, from whichever tier would install it. */
    fun search(query: String): List<Manifest> {
        val needle = query.rustTrim().asciiLowercase()
        val seen = HashSet<String>()
        return manifests()
            .filter { needle.isEmpty() || matchesQuery(it, needle) }
            .filter { seen.add(normalizeName(it.name)) }
            .toList()
    }

    /**
     * What `ketch list remote` offers: the fetched registry and the user's own manifests, one per name, the user's
     * winning as it would at install. The built-in tier is left out: it is a bootstrap copy of a few registry
     * entries, and listing it would make a machine that never ran `ketch update` look as if it had a registry.
     */
    fun listed(): List<Manifest> {
        val seen = HashSet<String>()
        return (user + registry).map { it.first }.filter { seen.add(normalizeName(it.name)) }.sortedBy { normalizeName(it.name) }
    }

    /** True when `ketch update` has not fetched a registry that holds anything. */
    fun registryIsEmpty(): Boolean = registry.isEmpty()

    /** Precedence order: user manifests shadow the registry, which shadows the built-ins. Everything that reads the tiers goes through this. */
    private fun manifests(): Sequence<Manifest> = user.asSequence().map { it.first } + registry.asSequence().map { it.first } + builtin.asSequence()

    private fun find(pred: (Manifest) -> Boolean): Pair<Manifest, ManifestOrigin>? =
        user.firstOrNull { pred(it.first) }?.let { (m, path) -> m to ManifestOrigin.User(path) }
            ?: registry.firstOrNull { pred(it.first) }?.let { (m, path) -> m to ManifestOrigin.Registry(path) }
            ?: builtin.firstOrNull(pred)?.let { it to ManifestOrigin.Builtin }

    companion object {
        /** The resolver for [ctx], over the registry `ketch update` fetched. */
        fun load(ctx: Ctx): Resolver = load(ctx, ketch.core.registry.load(ctx))

        /** The resolver for [ctx], over a [registry] already read. */
        fun load(
            ctx: Ctx,
            registry: List<Pair<Manifest, Path>>,
        ): Resolver =
            // A malformed built-in registry is a bug in ketch, not in the user's setup, so it fails loudly rather
            // than degrading to inference.
            Resolver(parseRegistry(BUILTIN_TOML, "the built-in registry"), registry, loadUserManifests(ctx.cfg.manifestDir, ctx.report))
    }
}

/** Read every `.toml` in the manifest directory. One unreadable file must not take down every command, so failures are reported and skipped. */
internal fun loadUserManifests(
    dir: Path,
    report: Report,
): List<Pair<Manifest, Path>> {
    val names =
        try {
            listDir(dir)
        } catch (_: FsException) {
            return emptyList()
        }
    return names.filter(::isTomlFileName).sorted().map { joinPath(dir, it) }.flatMap { path ->
        // Both error kinds already name the file, so the warning does not.
        try {
            parseRegistry(readText(path), path.toString()).map { it to path }
        } catch (e: FsException) {
            report.warn("ignoring manifest: ${KetchError.fsKit(path, e).message}")
            emptyList()
        } catch (e: KetchError) {
            report.warn("ignoring manifest: ${e.message}")
            emptyList()
        }
    }
}

/** Rust `Path::extension() == "toml"`: a dot file named `.toml` has no extension. */
private fun isTomlFileName(name: String): Boolean = name.lastIndexOf('.').let { it > 0 && name.substring(it + 1) == "toml" }
