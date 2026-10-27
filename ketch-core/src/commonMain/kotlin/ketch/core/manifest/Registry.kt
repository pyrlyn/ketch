package ketch.core.manifest

import ketch.core.error.KetchError
import ketch.core.model.Manifest
import ketch.core.model.PackageRef
import ketch.core.model.normalizeName
import ketch.core.text.asciiLowercase
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.tomlfile.Document
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The built-in registry: curated manifests for tools whose release layout needs a hint that inference cannot guess. */
val BUILTIN_TOML =
    """
    # Built-in package registry.
    #
    # Compiled into the binary. An entry is only needed when inference gets it
    # wrong — a repository whose release assets are named unusually, ships an app
    # bundle, or is better known by a short alias than by `owner/repo`.
    #
    # See docs/MANIFESTS.md for the full schema.

    # ketch itself, so `self install` resolves without the registry: on a fresh
    # machine nothing has fetched it yet. The same entry lives in the registry and
    # in the repository root as ketch.toml.
    [[package]]
    name = "ketch"
    source = "github:pyrlyn/ketch"
    description = "Catch releases straight from GitHub"
    homepage = "https://github.com/pyrlyn/ketch"

    [[package]]
    name = "ripgrep"
    source = "github:BurntSushi/ripgrep"
    description = "Recursively search directories for a regex pattern"
    homepage = "https://github.com/BurntSushi/ripgrep"
    bin = [{ name = "rg" }]
    provides = ["rg"]
    """.trimIndent() + "\n"

/**
 * A file holding several manifests, as `builtin.toml` does. Strict like `Manifest` itself: this is the shape a user
 * manifest takes, and a key outside `[[package]]` is a manifest that parsed as nothing at all.
 */
@Serializable
private class RegistryFile(
    @SerialName("package") val packages: List<Manifest> = emptyList(),
)

/** One file's manifests, a single manifest or a `[[package]]` array, each checked beyond what its type can. */
internal fun parseRegistry(
    text: String,
    what: String,
): List<Manifest> {
    val doc = Document.parse(text, what)
    // Decided from the parsed shape, not the source text: a single manifest that merely mentions `[[package]]` in a
    // description or a note is still a manifest, and sniffing for the string turned the whole file into no packages.
    val manifests = if (doc.isArray("package")) doc.deserialize<RegistryFile>().packages else listOf(doc.deserialize<Manifest>())
    for (manifest in manifests) {
        try {
            manifest.validate()
        } catch (e: KetchError) {
            throw KetchError.Parse(what, "package `${manifest.name}`: ${e.message}", e)
        }
    }
    return manifests
}

/**
 * Whether two references name the same package. GitHub owners and repositories are case-insensitive, so
 * `burntsushi/ripgrep` is the repository the curated `BurntSushi/ripgrep` entry describes; without this it resolves
 * by inference and quietly drops the curated `bin`, `provides` and asset rules. Every other scheme is compared
 * exactly: a `local:` path, on Linux, can differ only by case and be a different file.
 */
internal fun sameSource(
    a: PackageRef,
    b: PackageRef,
): Boolean =
    when {
        !a.scheme.eqIgnoreAsciiCase(b.scheme) -> false
        a.scheme.eqIgnoreAsciiCase("github") -> a.id.eqIgnoreAsciiCase(b.id)
        else -> a.id == b.id
    }

internal fun matchesQuery(
    manifest: Manifest,
    needle: String,
): Boolean =
    needle in manifest.name.asciiLowercase() ||
        needle in manifest.source.id.asciiLowercase() ||
        manifest.provides.any { needle in it.asciiLowercase() } ||
        manifest.description?.let { needle in it.asciiLowercase() } == true

internal fun answersTo(
    manifest: Manifest,
    alias: String,
): Boolean = normalizeName(manifest.name) == alias || manifest.provides.any { normalizeName(it) == alias }
