package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.http.API_JSON
import ketch.core.http.decodeApiJson
import ketch.core.model.Arch
import ketch.core.model.BinSpec
import ketch.core.model.Os
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.text.BYTE_ORDER
import ketch.core.text.asciiLowercase
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.rustTrim
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import yamlaot.Yaml
import yamlaot.YamlAotException
import yamlaot.YamlValue

/*
 * winget: a package from the community repository, `microsoft/winget-pkgs`.
 *
 * The community source has no public REST endpoint — the REST protocol is for private sources and the Store — so the
 * newest version folder is found with the GitHub contents API and its installer manifest read raw. The manifest is
 * YAML, in the multi-file form (`<Id>.installer.yaml`) or the older singleton (`<Id>.yaml`); both carry the same
 * `Installers` list.
 *
 * winget runs installers; ketch unpacks. So only the two installer types that are a file to place convert: `portable`
 * (a bare executable) and `zip` with a `portable` nested installer.
 */

@Serializable
private class NestedFile(
    @SerialName("RelativeFilePath") val relativeFilePath: String,
    @SerialName("PortableCommandAlias") val portableCommandAlias: String? = null,
)

/** The fields an installer inherits from the manifest root when it does not set them itself. */
private interface Common {
    val installerType: String?
    val nestedInstallerType: String?
    val nestedInstallerFiles: List<NestedFile>?
    val commands: List<String>?
    val scope: String?
    val installerLocale: String?
}

@Serializable
private class InstallerManifest(
    @SerialName("PackageIdentifier") val packageIdentifier: String,
    @SerialName("PackageVersion") val packageVersion: String,
    @SerialName("Installers") val installers: List<Installer> = emptyList(),
    @SerialName("InstallerType") override val installerType: String? = null,
    @SerialName("NestedInstallerType") override val nestedInstallerType: String? = null,
    @SerialName("NestedInstallerFiles") override val nestedInstallerFiles: List<NestedFile>? = null,
    @SerialName("Commands") override val commands: List<String>? = null,
    @SerialName("Scope") override val scope: String? = null,
    @SerialName("InstallerLocale") override val installerLocale: String? = null,
) : Common

@Serializable
private class Installer(
    @SerialName("Architecture") val architecture: String,
    @SerialName("InstallerUrl") val installerUrl: String,
    @SerialName("InstallerSha256") val installerSha256: String? = null,
    @SerialName("InstallerType") override val installerType: String? = null,
    @SerialName("NestedInstallerType") override val nestedInstallerType: String? = null,
    @SerialName("NestedInstallerFiles") override val nestedInstallerFiles: List<NestedFile>? = null,
    @SerialName("Commands") override val commands: List<String>? = null,
    @SerialName("Scope") override val scope: String? = null,
    @SerialName("InstallerLocale") override val installerLocale: String? = null,
) : Common

/** An installer with the root's defaults filled in, as winget reads it. */
private class Effective(
    private val root: Common,
    val installer: Installer,
) {
    private fun lower(pick: (Common) -> String?) = (pick(installer) ?: pick(root))?.rustTrim()?.asciiLowercase()

    val kind = lower { it.installerType }.orEmpty()
    val nested = lower { it.nestedInstallerType }
    val files = installer.nestedInstallerFiles ?: root.nestedInstallerFiles.orEmpty()
    val commands = installer.commands ?: root.commands.orEmpty()
    private val scope = lower { it.scope }
    private val locale = installer.installerLocale ?: root.installerLocale

    /** Whether this is a file ketch can place rather than a program to run. */
    val placeable get() = kind == "portable" || (kind == "zip" && nested == "portable")

    /** `zip/msi` for a zip around an installer, the type otherwise. */
    fun describe() = if (kind == "zip" && nested != null) "zip/$nested" else kind

    /** The order winget would offer: no locale or English first, then the per-user install over the machine-wide one. Lower is better. */
    val rank: Int
        get() {
            val byLocale =
                when {
                    locale == null || locale.eqIgnoreAsciiCase("en-US") -> 0
                    locale.asciiLowercase().startsWith("en") -> 1
                    else -> 2
                }
            val byScope =
                when (scope) {
                    "user" -> 0
                    null -> 1
                    else -> 2
                }
            return byLocale * RANKS + byScope
        }
}

private const val RANKS = 3

/** The targets a winget architecture covers. `x86` and 32-bit `arm` have no ketch target; `neutral` runs anywhere Windows does. */
private fun targets(architecture: String): List<TargetSpec> =
    when (architecture.rustTrim().asciiLowercase()) {
        "x64" -> listOf(Arch.X86_64)
        "arm64" -> listOf(Arch.Aarch64)
        "neutral" -> listOf(Arch.X86_64, Arch.Aarch64)
        else -> emptyList()
    }.map { TargetSpec(Os.Windows, it) }

/**
 * YAML as the JSON the manifest classes read. Every scalar becomes its text, so `PackageVersion: 2.0` stays "2.0"
 * rather than becoming a number: no field of a manifest is anything but text.
 */
private fun YamlValue.toJson(): JsonElement =
    when (this) {
        YamlValue.Null -> JsonNull
        is YamlValue.Bool -> JsonPrimitive(value.toString())
        is YamlValue.Int -> JsonPrimitive(value.toString())
        is YamlValue.Float -> JsonPrimitive(text)
        is YamlValue.Str -> JsonPrimitive(value)
        is YamlValue.Seq -> JsonArray(items.map { it.toJson() })
        is YamlValue.Map -> JsonObject(entries.mapValues { it.value.toJson() })
    }

private const val MANIFEST = "winget installer manifest"

private fun readManifest(yaml: String): InstallerManifest {
    val json =
        try {
            Yaml.parse(yaml).toJson()
        } catch (e: YamlAotException) {
            throw KetchError.Parse(MANIFEST, e.message.orEmpty(), e)
        }
    return try {
        API_JSON.decodeFromJsonElement(InstallerManifest.serializer(), json)
    } catch (e: IllegalArgumentException) {
        throw KetchError.Parse(MANIFEST, e.message ?: e.toString(), e)
    }
}

/** Convert an installer manifest (multi-file or singleton YAML). */
internal fun convertWinget(yaml: String): Conversion {
    val manifest = readManifest(yaml)
    val id = manifest.packageIdentifier.rustTrim()
    val byTarget = mutableMapOf<String, Pair<TargetSpec, MutableList<Effective>>>()
    for (installer in manifest.installers) {
        for (target in targets(installer.architecture)) {
            byTarget.getOrPut(target.toString()) { target to mutableListOf() }.second += Effective(manifest, installer)
        }
    }

    val downloads = mutableListOf<Download>()
    var refused: String? = null
    val chosenAll = mutableListOf<Effective>()
    for (key in byTarget.keys.sortedWith(BYTE_ORDER)) {
        val (target, candidates) = byTarget.getValue(key)
        val placeable = candidates.filter { it.placeable }
        if (placeable.isEmpty() && refused == null) {
            refused = "it installs with a winget `${candidates.first().describe()}` installer, which ketch can't run"
        }
        val pool = if (placeable.isEmpty()) candidates else placeable.filter { it.rank == placeable.minOf { p -> p.rank } }
        for (e in pool.distinctBy { it.installer.installerUrl }) {
            downloads += Download(target, e.installer.installerUrl, e.installer.installerSha256)
            chosenAll += e
        }
    }

    val name = id.split('.').getOrNull(1) ?: id
    val bins = chosenAll.firstOrNull()?.let { binsOf(it, name) }.orEmpty()
    val verdict = assemble(Backend.Winget, id, name, manifest.packageVersion, PackageKind.Auto, bins, downloads)
    return if (verdict is Converted && refused != null) Rejected.Unsupported(refused) else verdict
}

/** The commands winget would put on `PATH` for this installer. */
private fun binsOf(
    e: Effective,
    name: String,
): List<BinSpec> {
    if (e.kind != "zip") return listOfNotNull(binEntry(e.commands.firstOrNull() ?: name, null))
    return e.files.mapNotNull { f ->
        val file = f.relativeFilePath.replace('\\', '/')
        binEntry(f.portableCommandAlias ?: file.substringAfterLast('/'), file)
    }
}

@Serializable
private class Entry(
    val name: String,
    @SerialName("type") val kind: String,
)

/** winget's own ordering of version folder names: dotted parts compared as numbers when both are, as text otherwise, a missing part counting as 0. */
internal fun compareVersions(
    a: String,
    b: String,
): Int {
    val pa = a.split('.', '-')
    val pb = b.split('.', '-')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { "0" }
        val y = pb.getOrElse(i) { "0" }
        val m = x.toULongOrNull()
        val n = y.toULongOrNull()
        val order = if (m != null && n != null) m.compareTo(n) else BYTE_ORDER.compare(x, y)
        if (order != 0) return order
    }
    return 0
}

/** Look a package identifier up and convert its newest version. */
fun lookupWinget(
    fetch: Fetch,
    endpoints: Endpoints,
    raw: String,
): Found {
    val id = checkName(raw)
    val dir = wingetDir(id)
    val version = newestVersion(fetch, "${endpoints.wingetApi}/$dir", id)
    val base = "${endpoints.wingetRaw}/$dir/$version"
    val yaml =
        fetch.text("$base/$id.installer.yaml")
            ?: fetch.text("$base/$id.yaml")
            ?: throw KetchError.Msg("winget-pkgs has `$id` $version but no installer manifest in it")
    return found(id, convertWinget(yaml))
}

/** `m/Microsoft/PowerToys` for `Microsoft.PowerToys`: where winget-pkgs keeps an identifier. */
private fun wingetDir(id: String): String {
    val segments = id.split('.')
    if (segments.size < 2 || segments.any { it.isEmpty() }) {
        throw KetchError.Msg("`$id` is not a winget package identifier: expected `Publisher.Package`")
    }
    return "${id.take(1).asciiLowercase()}/${segments.joinToString("/")}"
}

private fun newestVersion(
    fetch: Fetch,
    url: String,
    id: String,
): String {
    val missing = "winget has no package `$id` (identifiers are case-sensitive: `winget search` shows the exact one)"
    val listing = fetch.text(url) ?: throw KetchError.Msg(missing)
    // A package folder holds its version folders and, sometimes, the folders of packages named under it
    // (`Google.Chrome.Canary`), which start with a letter.
    return decodeApiJson<List<Entry>>("winget-pkgs folder listing", listing)
        .filter { it.kind == "dir" && it.name.firstOrNull() in '0'..'9' }
        .map { it.name }
        // The last of equals, as Rust's `max_by` keeps.
        .reduceOrNull { best, next -> if (compareVersions(next, best) >= 0) next else best }
        ?: throw KetchError.Msg(missing)
}
