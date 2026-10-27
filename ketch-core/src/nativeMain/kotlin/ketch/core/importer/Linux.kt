package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.http.decodeApiJson
import ketch.core.model.Arch
import ketch.core.model.Os
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.text.BYTE_ORDER
import ketch.core.text.asciiLowercase
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.rustTrim
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Linux: a package from Arch Linux — the official repositories, then the AUR.
 *
 * No cross-distribution catalogue names an artifact URL, and the distribution repositories build from source. Arch is
 * the exception worth having: the AUR's `-bin` packages repackage what upstream published, and every package's
 * `.SRCINFO` lists its downloads per architecture with their checksums, in a format meant for machines.
 *
 * A name is looked for in four places, in this order: the official repositories, the AUR package of that exact name,
 * `<name>-bin`, and AUR packages that `provide` the name. The first that converts wins — unless the ones that convert
 * are different projects, which is refused rather than guessed.
 */

/** The parts of a `.SRCINFO` a conversion reads. */
private data class SrcInfo(
    val pkgbase: String = "",
    val pkgver: String = "",
    val arch: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    /** `source` and `source_<arch>`, keyed by the suffix ("" for none). */
    val sources: Map<String, List<String>> = emptyMap(),
    /** `sha256sums` and `sha256sums_<arch>`, index-aligned with [sources]. */
    val sha256: Map<String, List<String>> = emptyMap(),
)

/**
 * Read a `.SRCINFO`, keeping the base section and the one `pkgname` section asked for, which may override `arch` and
 * `provides`.
 */
private fun parseSrcinfo(
    text: String,
    pkgname: String,
): SrcInfo {
    val base = SrcInfoBuilder()
    var section: SrcInfoBuilder? = null
    // null while in the base section; true inside the wanted package.
    var inPackage: Boolean? = null
    for (line in text.lines()) {
        val parts = line.rustTrim().split(" = ", limit = 2)
        if (parts.size < 2) continue
        val key = parts[0].rustTrim()
        val value = parts[1].rustTrim()
        when {
            key == "pkgname" -> {
                inPackage = value == pkgname
                if (inPackage) section = section ?: SrcInfoBuilder()
            }

            inPackage == true -> {
                checkNotNull(section).readOverride(key, value)
            }

            inPackage == null -> {
                base.readBase(key, value)
            }
        }
    }
    return base.build(section)
}

private class SrcInfoBuilder {
    var pkgbase = ""
    var pkgver = ""
    var arch: MutableList<String>? = null
    var provides: MutableList<String>? = null
    val sources = mutableMapOf<String, MutableList<String>>()
    val sha256 = mutableMapOf<String, MutableList<String>>()

    fun readOverride(
        key: String,
        value: String,
    ) {
        when (key) {
            "arch" -> arch = (arch ?: mutableListOf()).apply { add(value) }
            "provides" -> provides = (provides ?: mutableListOf()).apply { add(value) }
        }
    }

    fun readBase(
        key: String,
        value: String,
    ) {
        when {
            key == "pkgbase" -> pkgbase = value
            key == "pkgver" -> pkgver = value
            key == "arch" || key == "provides" -> readOverride(key, value)
            key.startsWith("source") -> sources.getOrPut(key.removePrefix("source").trimStart('_')) { mutableListOf() } += value
            key.startsWith("sha256sums") -> sha256.getOrPut(key.removePrefix("sha256sums").trimStart('_')) { mutableListOf() } += value
        }
    }

    fun build(section: SrcInfoBuilder?) =
        SrcInfo(
            pkgbase = pkgbase,
            pkgver = pkgver,
            arch = section?.arch ?: arch.orEmpty(),
            provides = section?.provides ?: provides.orEmpty(),
            sources = sources,
            sha256 = sha256,
        )
}

/**
 * `name::https://…` or `https://…`: the URL, when this source is remote. A local file is packaging (a `.desktop`, a
 * wrapper script), and a VCS source (`git+https://`) is a checkout, not a download.
 */
private fun remoteUrl(source: String): String? {
    val url = source.substringAfter("::")
    return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

private val ARCHIVES = listOf(".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz", ".tar.zst", ".zip", ".gz", ".xz", ".appimage")

/**
 * Whether a download is the package itself rather than something packaged beside it (a licence, an icon): an
 * archive, an AppImage, a distribution package, or a file whose name says which platform it is for.
 */
private fun isPayload(url: String): Boolean {
    val file = url.substringAfterLast('/').asciiLowercase()
    return ARCHIVES.any { file.endsWith(it) } || foreignPackage(file) != null || namesAPlatform(file) || '.' !in file
}

/** The ketch targets an Arch `arch` value covers. */
private fun targetsOf(arch: String): List<TargetSpec> =
    when (arch) {
        "x86_64" -> listOf(Arch.X86_64)
        "aarch64" -> listOf(Arch.Aarch64)
        "any" -> listOf(Arch.X86_64, Arch.Aarch64)
        else -> emptyList()
    }.map { TargetSpec(Os.Linux, it) }

/** The install name: the package name without the packaging suffix. */
private fun baseName(pkgname: String): String =
    listOf("-bin", "-appimage", "-git").firstNotNullOfOrNull { suffix ->
        pkgname.takeIf { it.endsWith(suffix) }?.dropLast(suffix.length)?.takeIf { it.isNotEmpty() }
    } ?: pkgname

/** Convert one package's `.SRCINFO`. */
internal fun convertSrcinfo(
    pkgname: String,
    srcinfo: String,
): Conversion {
    val info = parseSrcinfo(srcinfo, pkgname)
    val downloads = downloadsOf(info)
    val name = baseName(pkgname)
    val command =
        info.provides
            .map { it.split('=', '<', '>').first().rustTrim() }
            .firstOrNull { it.isNotEmpty() && ".so" !in it } ?: name
    // A bare download (an AppImage) is the executable itself, named however upstream named it; an archive is
    // searched for the command by name.
    val singleFile =
        downloads.firstOrNull()?.let { releaseUrl(it.url) }?.file?.takeIf {
            val lower = it.asciiLowercase()
            lower.endsWith(".appimage") || '.' !in lower
        }
    return assemble(Backend.Linux, pkgname, name, info.pkgver, PackageKind.Auto, listOfNotNull(binEntry(command, singleFile)), downloads)
}

private fun downloadsOf(info: SrcInfo): List<Download> {
    val perArch = info.sources.keys.any { it.isNotEmpty() }
    return info.arch.flatMap { arch ->
        // With per-architecture sources, the shared `source` list is the packaging around them; without, it is the
        // download for every arch.
        val key = if (perArch) arch else ""
        val sums = info.sha256[key].orEmpty()
        val payloads =
            info.sources[key]
                .orEmpty()
                .mapIndexedNotNull { i, s -> remoteUrl(s)?.let { it to sums.getOrNull(i) } }
                .filter { (url, _) -> isPayload(url) }
        // A package that offers its own `.deb` beside an AppImage or a tarball means the one ketch can unpack.
        val chosen = payloads.filter { (url, _) -> foreignPackage(url) == null }.ifEmpty { payloads }
        targetsOf(arch).flatMap { target -> chosen.map { (url, sha) -> Download(target, url, sha) } }
    }
}

@Serializable
private class OfficialSearch(
    val results: List<OfficialPackage> = emptyList(),
)

@Serializable
private class OfficialPackage(
    val pkgname: String,
    val pkgbase: String,
    val repo: String,
)

@Serializable
private class AurResponse(
    val results: List<AurPackage> = emptyList(),
    val error: String? = null,
)

@Serializable
private class AurPackage(
    @SerialName("Name") val name: String,
    @SerialName("PackageBase") val packageBase: String,
)

/** One place a name was found. */
private class Candidate(
    val pkgname: String,
    val pkgbase: String,
    /** "Arch [extra]" or "the AUR", for messages. */
    val origin: String,
    val official: Boolean,
)

private fun aur(
    fetch: Fetch,
    url: String,
): List<AurPackage> {
    val body = fetch.text(url) ?: return emptyList()
    val response = decodeApiJson<AurResponse>("AUR RPC response", body)
    response.error?.let { throw KetchError.Msg("the AUR refused the query: $it") }
    return response.results
}

private fun official(
    fetch: Fetch,
    e: Endpoints,
    name: String,
    q: String,
): Candidate? {
    val body = fetch.text("${e.arch}/packages/search/json/?name=$q") ?: return null
    val search = decodeApiJson<OfficialSearch>("Arch package search", body)
    return search.results
        .find { it.pkgname == name && "testing" !in it.repo && "staging" !in it.repo }
        ?.let { Candidate(it.pkgname, it.pkgbase, "Arch [${it.repo}]", official = true) }
}

private fun candidates(
    fetch: Fetch,
    e: Endpoints,
    name: String,
): List<Candidate> {
    // `+` is literal in Arch names but a space in a query string.
    val q = name.replace("+", "%2B")
    val out = listOfNotNull(official(fetch, e, name, q)).toMutableList()
    val infoUrl = "${e.aur}/rpc/v5/info?arg[]=$q" + if (name.endsWith("-bin")) "" else "&arg[]=$q-bin"
    // The exact name first, then `-bin`, whatever order the AUR answered in.
    val aurFound = aur(fetch, infoUrl).sortedBy { it.name != name }
    val prebuilt =
        aur(fetch, "${e.aur}/rpc/v5/search/$q?by=provides")
            .filter { it.name.endsWith("-bin") || it.name.endsWith("-appimage") }
            .sortedWith(compareBy(BYTE_ORDER) { it.name })
    for (p in aurFound + prebuilt) {
        if (out.none { it.pkgname == p.name }) out += Candidate(p.name, p.packageBase, "the AUR", official = false)
    }
    return out
}

private fun verdictOf(
    fetch: Fetch,
    e: Endpoints,
    candidate: Candidate,
): Conversion {
    val url =
        if (candidate.official) {
            "${e.archGitlab}/${candidate.pkgbase}/-/raw/main/.SRCINFO"
        } else {
            "${e.aur}/cgit/aur.git/plain/.SRCINFO?h=${candidate.pkgbase}"
        }
    return fetch.text(url)?.let { convertSrcinfo(candidate.pkgname, it) }
        ?: Rejected.Unsupported("${candidate.origin} lists it, but its .SRCINFO could not be read")
}

/** Look [raw] up in Arch Linux and convert the package it settles on. */
fun lookupLinux(
    fetch: Fetch,
    endpoints: Endpoints,
    raw: String,
): Found {
    val name = checkName(raw)
    val found = candidates(fetch, endpoints, name)
    if (found.isEmpty()) throw KetchError.Msg("Arch Linux has no package named `$name`, in the official repositories or the AUR")
    val verdicts = found.map { it to verdictOf(fetch, endpoints, it) }
    val converted = verdicts.mapNotNull { (c, v) -> (v as? Converted)?.let { c to it } }
    // Nothing converts: the reason given is the first place's, the one a user asking for this exact name meant.
    val (first, chosen) = converted.find { it.first.pkgname == name } ?: converted.firstOrNull() ?: return found(name, verdicts[0].second)
    if (first.pkgname != name) refuseAmbiguous(name, first, chosen, converted)
    val skipped = verdicts.find { (c, v) -> c.official && v is Rejected && c.pkgname != first.pkgname }?.first
    val notes =
        when {
            skipped != null -> listOf("${skipped.origin} ${skipped.pkgname} builds from source; using ${first.origin}'s ${first.pkgname}")
            first.pkgname != name -> listOf("using ${first.origin}'s ${first.pkgname}")
            else -> emptyList()
        }
    return Found(chosen, notes)
}

/** Packages that only provide [name] and come from different projects are refused rather than guessed between. */
private fun refuseAmbiguous(
    name: String,
    first: Candidate,
    chosen: Converted,
    converted: List<Pair<Candidate, Converted>>,
) {
    val others = converted.filterNot { (_, c) -> c.repo.eqIgnoreAsciiCase(chosen.repo) }.map { (cand, c) -> "${cand.pkgname} (${c.repo})" }
    if (others.isNotEmpty()) {
        throw KetchError.Msg(
            "`$name` is ambiguous in Arch Linux: ${first.pkgname} (${chosen.repo}) and ${others.joinToString(", ")}; import one of them by its exact name",
        )
    }
}
