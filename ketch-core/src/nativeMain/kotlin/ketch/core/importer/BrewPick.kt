package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.http.decodeApiJson
import ketch.core.model.Arch
import ketch.core.model.BinSpec
import ketch.core.model.Os
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Homebrew: a formula or a cask, from `formulae.brew.sh`'s JSON API.
 *
 * A cask downloads what the project published, so a cask whose `url` is a GitHub release asset converts. A formula is
 * a build recipe: its `urls.stable` is the source it compiles, and the binaries Homebrew ships are bottles on
 * `ghcr.io`. So a formula converts only in the rare case that its stable URL is a release asset naming a platform, and
 * is "not distributed through GitHub Releases" otherwise — which, for homebrew-core, is every formula there is.
 */

/** Which half of Homebrew to look in. */
enum class BrewPick {
    /** Both; a name that is both is settled by which one converts. */
    Either,
    Formula,
    Cask,
}

@Serializable
private class Cask(
    val token: String,
    val version: String,
    val url: String,
    val sha256: String? = null,
    val artifacts: List<JsonObject> = emptyList(),
    /**
     * In the order it was written (kotlinx reads a map into a `LinkedHashMap`): a cask's `variations` list macOS
     * releases newest first, and the newest Intel one is the one that matters.
     */
    val variations: Map<String, Variation>? = null,
)

@Serializable
private class Variation(
    val url: String? = null,
    val sha256: String? = null,
)

@Serializable
private class Formula(
    val name: String,
    val versions: FormulaVersions,
    val urls: FormulaUrls,
)

@Serializable
private class FormulaVersions(
    val stable: String? = null,
)

@Serializable
private class FormulaUrls(
    val stable: FormulaUrl? = null,
)

@Serializable
private class FormulaUrl(
    val url: String,
    val checksum: String? = null,
)

/** Cask artifacts that only say how Homebrew cleans up or what it prints: ketch has its own uninstall, and nothing here changes what is installed. */
private val IGNORED_ARTIFACTS =
    setOf("zap", "uninstall", "caveats", "bash_completion", "zsh_completion", "fish_completion", "manpage", "generate_completions_from_executable")

private fun checksum(sha256: String?) = sha256?.takeIf { it != "no_check" }

/** Convert a cask's API JSON. */
fun convertCask(json: String): Conversion {
    val cask = decodeApiJson<Cask>("Homebrew cask JSON", json)
    // The top level is the arm64 download; the first variation without an `arm64_` prefix is the newest Intel macOS.
    // Linux variations are not macOS downloads at all.
    val intel =
        cask.variations
            .orEmpty()
            .entries
            .firstOrNull { (key, v) -> !key.startsWith("arm64_") && "linux" !in key && v.url != null }
            ?.value
    val downloads =
        listOf(
            Download(TargetSpec(Os.MacOs, Arch.Aarch64), cask.url, checksum(cask.sha256)),
            Download(TargetSpec(Os.MacOs, Arch.X86_64), intel?.url ?: cask.url, checksum(if (intel != null) intel.sha256 else cask.sha256)),
        )
    val payload = caskPayload(cask.artifacts)
    val converted = assemble(Backend.Brew, cask.token, cask.token, cask.version, payload.kind, payload.bins, downloads)
    return if (converted is Converted && payload.refused != null) Rejected.Unsupported(payload.refused) else converted
}

/** What a cask installs, as ketch would: an app, or binaries; [refused] is why it cannot, when it cannot. */
private class Payload(
    val kind: PackageKind,
    val bins: List<BinSpec>,
    val refused: String?,
)

private fun caskPayload(artifacts: List<JsonObject>): Payload {
    var app = false
    val bins = mutableListOf<BinSpec>()
    for (artifact in artifacts) {
        when (val key = artifact.keys.firstOrNull { it != "target" }) {
            null -> {}

            "app" -> {
                app = true
            }

            "binary" -> {
                caskBinary(artifact)?.let { bins += it }
            }

            in IGNORED_ARTIFACTS -> {}

            else -> {
                return Payload(PackageKind.Auto, emptyList(), "it installs with a `$key` artifact, which ketch can't place yet")
            }
        }
    }
    return when {
        // An app's command-line helpers stay inside the bundle: `kind = "app"` places the bundle and links nothing
        // (docs/MANIFESTS.md).
        app -> Payload(PackageKind.App, emptyList(), null)

        bins.isEmpty() -> Payload(PackageKind.Auto, emptyList(), "it has no app and no binary for ketch to install")

        else -> Payload(PackageKind.Auto, bins, null)
    }
}

private fun caskBinary(artifact: JsonObject): BinSpec? {
    val source = ((artifact["binary"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    // Binaries inside the bundle come with the app; ketch places the bundle whole.
    if (source.startsWith("\$APPDIR")) return null
    val command = (artifact["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: source
    return binEntry(command.substringAfterLast('/'), source)
}

/** Convert a formula's API JSON. */
fun convertFormula(json: String): Conversion {
    val formula = decodeApiJson<Formula>("Homebrew formula JSON", json)
    val stable = formula.urls.stable
    val release = stable?.let { releaseUrl(it.url) }
    // A release asset that names no platform is the project's source tarball, attached to the release for
    // convenience: still source only.
    if (stable == null || release == null || !namesAPlatform(release.file)) return Rejected.NotGithubReleases
    val downloads = targetsNamed(release.file).map { Download(it, stable.url, stable.checksum) }
    return assemble(Backend.Brew, formula.name, formula.name, formula.versions.stable.orEmpty(), PackageKind.Auto, listOfNotNull(binEntry(formula.name, null)), downloads)
}

/** Look [raw] up and convert it. */
fun lookupBrew(
    fetch: Fetch,
    endpoints: Endpoints,
    raw: String,
    pick: BrewPick,
): Found {
    val name = checkName(raw)
    val formula = if (pick == BrewPick.Cask) null else fetch.text("${endpoints.brew}/formula/$name.json")?.let(::convertFormula)
    val cask = if (pick == BrewPick.Formula) null else fetch.text("${endpoints.brew}/cask/$name.json")?.let(::convertCask)
    return when {
        formula == null && cask == null -> {
            throw KetchError.Msg(noneNamed(name, pick))
        }

        formula == null || cask == null -> {
            found(name, checkNotNull(formula ?: cask))
        }

        formula is Converted && cask is Converted -> {
            throw KetchError.Msg("`$name` is both a Homebrew formula and a cask; pass --formula or --cask")
        }

        formula is Converted -> {
            Found(formula, listOf("`$name` is also a cask, which does not convert; using the formula"))
        }

        cask is Converted -> {
            Found(cask, listOf("`$name` is also a formula, which builds from source; using the cask"))
        }

        // Homebrew's own default for a bare name is the formula, so its reason is the one given.
        else -> {
            found(name, formula)
        }
    }
}

private fun noneNamed(
    name: String,
    pick: BrewPick,
) = when (pick) {
    BrewPick.Either -> "Homebrew has no formula or cask named `$name`"
    BrewPick.Formula -> "Homebrew has no formula named `$name`"
    BrewPick.Cask -> "Homebrew has no cask named `$name`"
}

/** A single verdict as the command reports it: the package, or the error its rejection becomes. */
internal fun found(
    name: String,
    verdict: Conversion,
): Found =
    when (verdict) {
        is Converted -> Found(verdict)
        is Rejected -> throw verdict.toError(name)
    }
