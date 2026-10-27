package ketch.core.importer

import ketch.core.config.sanitizeComponent
import ketch.core.config.validateRepo
import ketch.core.error.KetchError
import ketch.core.model.Arch
import ketch.core.model.AssetSelector
import ketch.core.model.BinSpec
import ketch.core.model.Manifest
import ketch.core.model.Os
import ketch.core.model.PackageKind
import ketch.core.model.PackageRef
import ketch.core.model.TargetSpec
import ketch.core.model.normalizeName
import ketch.core.text.BYTE_ORDER
import ketch.core.text.asciiLowercase
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.escapeDebug
import ketch.core.text.isAsciiAlphanumeric
import ketch.core.text.rustTrim
import ketch.core.wizard.renderWithHeader

/*
 * `ketch import`: a package from another package manager, as a ketch manifest.
 *
 * Each backend looks a name up in its own catalogue and reduces what it finds to the same plain [Converted]: one GitHub
 * repository, one release tag, and the file that catalogue would download for each target. The rules every backend
 * shares live here, so "is this a GitHub release?" has one answer whichever catalogue asked it.
 *
 * The source's own definition is untrusted input — someone else wrote the formula, the winget manifest, the PKGBUILD —
 * so nothing from it reaches the manifest unchecked: the repository goes through [validateRepo], names through
 * [Manifest.validate], and only the handful of fields ketch needs is carried over at all.
 */

/** Which catalogue a package is imported from. */
enum class Backend(
    private val display: String,
) {
    /** The Windows Package Manager's community repository. */
    Winget("winget"),

    /** Homebrew: formulae and casks. */
    Brew("brew"),

    /** Arch Linux: the official repositories and the AUR. */
    Linux("linux"),
    ;

    override fun toString() = display
}

/** The file a catalogue downloads for one target. */
data class Artifact(
    /** The release asset's file name. */
    val file: String,
    /** Lowercase hex SHA-256 the catalogue recorded, when it recorded one. */
    val sha256: String?,
)

/** A converter's verdict on one package: [Converted], or [Rejected] saying why not. */
sealed interface Conversion

/** A package another manager describes, reduced to what ketch needs. */
data class Converted(
    val backend: Backend,
    /** The catalogue's own identifier: a cask token, a winget id, an AUR name. */
    val packageId: String,
    /** The install name, from the catalogue's name for the package. */
    val name: String,
    /** The version the catalogue gives, for messages. */
    val version: String,
    /** `owner/repo`, already validated. */
    val repo: String,
    /** The release tag every artifact comes from; null for a `releases/latest/download/` URL, which names no tag. */
    val tag: String?,
    /** `App` for a macOS bundle, `Auto` otherwise. */
    val kind: PackageKind,
    /** What to put on `PATH`. Empty only for an app. */
    val bins: List<BinSpec>,
    /** Per target (`TargetSpec` display form, in byte order), the asset that catalogue uses. */
    val artifacts: Map<String, Artifact>,
) : Conversion {
    /** The manifest, with nothing in it ketch would not use. */
    fun manifest(): Manifest {
        val target = artifacts.mapValues { (_, artifact) -> assetGlob(artifact.file, this) }
        // A path naming the versioned file would stop matching at the next release, the same as an asset pin would.
        val bin = bins.map { BinSpec(path = it.path?.let { p -> assetGlob(p, this) }, name = it.name) }
        val manifest =
            Manifest.inferred(PackageRef.github(repo)).copy(
                kind = kind,
                bin = bin,
                asset = AssetSelector(target = target),
                name = name,
            )
        manifest.validate()
        return manifest
    }

    /** The first line of the file, which also marks it as `ketch import`'s to rewrite. */
    fun header() = "$IMPORT_HEADER $backend $packageId`. Schema: docs/MANIFESTS.md.\n"

    /** The whole user manifest file. */
    fun render(): String = renderWithHeader(manifest(), header())

    /** The artifact this catalogue chose for [target], if it covers it. */
    fun artifactFor(target: TargetSpec): Artifact? = artifacts[target.toString()]
}

/** Why a package found in a catalogue does not convert. */
sealed interface Rejected : Conversion {
    /** At least one artifact is not a GitHub release asset. */
    data object NotGithubReleases : Rejected

    /** It is on GitHub Releases, but in a shape ketch cannot install; [why] completes "`<name>` can't be converted: …". */
    data class Unsupported(
        val why: String,
    ) : Rejected

    /** The error the command exits with. */
    fun toError(name: String): KetchError =
        when (this) {
            NotGithubReleases -> KetchError.Msg(notGithubMessage(name))
            is Unsupported -> KetchError.Msg("$name can't be converted: $why.")
        }
}

/** What a lookup settled on, and anything about the choice worth saying. */
data class Found(
    val converted: Converted,
    /** Lines for the user: which of several candidates was taken, and why. */
    val notes: List<String> = emptyList(),
)

/** The one sentence the hard rule speaks in, kept in one place so every backend and every test says it the same way. */
fun notGithubMessage(name: String) = "$name can't be converted: it is not distributed through GitHub Releases, and that is not supported yet."

private const val MAX_NAME = 128

/**
 * A name as typed, checked before it is put into a URL path or query.
 *
 * Every catalogue's identifiers fit this set (winget ids are dotted, cask tokens may carry `@`, Arch names `+`);
 * anything outside it is not a name any of them would answer to, and refusing it here means no backend has to think
 * about escaping.
 */
fun checkName(raw: String): String {
    val name = raw.rustTrim()
    val usable =
        name.isNotEmpty() &&
            name.length <= MAX_NAME &&
            name.all { it.isAsciiAlphanumeric() || it in ".-_+@" } &&
            name[0] !in ".-"
    if (!usable) throw KetchError.Msg("`${escapeDebug(name)}` is not a package name")
    return name
}

/** A GitHub release download URL, taken apart. */
internal data class ReleaseUrl(
    val repo: String,
    val tag: String?,
    val file: String,
)

/** `<owner>/<repo>/releases/download/<tag>/<file>` or `…/releases/latest/download/<file>`, with no query or fragment. */
private val RELEASE_PATH = Regex("(?<owner>[^/?#]+)/(?<repo>[^/?#]+)/releases/(?:download/(?<tag>[^/?#]+)|latest/download)/(?<file>[^/?#]+)")

/**
 * `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`, or `…/releases/latest/download/<file>`; null for
 * anything else.
 *
 * Deliberately narrow. A GitHub homepage, an `archive/refs/tags/` source tarball, `codeload`,
 * `objects.githubusercontent.com` and a raw file are all on GitHub without being a release asset someone published, and
 * the rule is about what was published.
 */
internal fun releaseUrl(url: String): ReleaseUrl? {
    val rest = url.removePrefix("https://")
    val match = RELEASE_PATH.matchEntire(rest.substringAfter('/', ""))
    if (rest == url || !rest.substringBefore('/').eqIgnoreAsciiCase("github.com") || match == null) return null
    val repo =
        try {
            validateRepo("release URL", "${match.groups["owner"]?.value}/${match.groups["repo"]?.value}")
        } catch (_: KetchError) {
            null
        }
    // A malformed escape decodes to nothing, which the emptiness checks below refuse.
    val tag = match.groups["tag"]?.value?.let { percentDecode(it).orEmpty() }
    val file = percentDecode(match.groups["file"]?.value.orEmpty()).orEmpty()
    val usable = repo != null && tag != "" && file.isNotEmpty() && '/' !in file && '\\' !in file
    return if (usable) ReleaseUrl(repo, tag, file) else null
}

private const val HEX = 16
private const val PERCENT = '%'.code.toByte()

/** `%` and two hex digits. */
private const val ESCAPE_LEN = 3

/** `%XX` decoding for a path segment; null for a malformed escape or bytes that are not UTF-8. */
private fun percentDecode(raw: String): String? {
    val bytes = raw.encodeToByteArray()
    val out = ArrayList<Byte>(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val escaped = bytes[i] == PERCENT
        out += if (escaped) hexByte(bytes, i + 1) ?: return null else bytes[i]
        i += if (escaped) ESCAPE_LEN else 1
    }
    return utf8OrNull(out.toByteArray())
}

private fun hexByte(
    bytes: ByteArray,
    at: Int,
): Byte? = if (at + ESCAPE_LEN - 1 > bytes.size) null else bytes.decodeToString(at, at + ESCAPE_LEN - 1).toUByteOrNull(HEX)?.toByte()

private fun utf8OrNull(bytes: ByteArray): String? =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

/** Package formats some other installer unpacks, which ketch cannot. */
private val FOREIGN_PACKAGES =
    listOf(".msi", ".msix", ".msixbundle", ".appx", ".appxbundle", ".deb", ".rpm", ".snap", ".flatpak", ".pkg.tar.zst", ".pkg.tar.xz")

/** The format, when [file] is a package for another installer. */
internal fun foreignPackage(file: String): String? {
    val lower = file.asciiLowercase()
    return FOREIGN_PACKAGES.firstOrNull { lower.endsWith(it) }
}

/** Whether a file name carries [token]. Word-sized tokens (`mac`, `win`, `gnu`) only match whole words, or `darwin` would contain `win`. */
private class Tokens(
    file: String,
) {
    private val lower = file.asciiLowercase()
    private val words = lower.split(NON_WORD).filter { it.isNotEmpty() }.toSet()

    fun has(token: String) = if (token.all { it.isAsciiAlphanumeric() }) token in words else token in lower

    fun hasAny(tokens: List<String>) = tokens.any(::has)

    private companion object {
        val NON_WORD = Regex("[^A-Za-z0-9]")
    }
}

/**
 * True when [file] names an operating system and an architecture, the way a prebuilt release asset does.
 *
 * A project that publishes binaries publishes one per platform and says which in the name; a source archive is the
 * same for everyone and says neither.
 */
internal fun namesAPlatform(file: String): Boolean {
    val tokens = Tokens(file)
    val os = listOf(Os.MacOs, Os.Linux, Os.Windows).any { tokens.hasAny(it.tokens) }
    val arch = listOf(Arch.Aarch64, Arch.X86_64, Arch.Universal).any { tokens.hasAny(it.tokens) }
    return os && arch
}

/** The targets a prebuilt asset's name says it is for: both macOS architectures for a universal build, nothing when it names no OS. */
internal fun targetsNamed(file: String): List<TargetSpec> {
    val tokens = Tokens(file)
    val os = listOf(Os.MacOs, Os.Windows, Os.Linux).firstOrNull { tokens.hasAny(it.tokens) } ?: return emptyList()
    val arches =
        when {
            tokens.hasAny(Arch.Aarch64.tokens) -> listOf(Arch.Aarch64)
            tokens.hasAny(Arch.X86_64.tokens) -> listOf(Arch.X86_64)
            else -> listOf(Arch.Aarch64, Arch.X86_64)
        }
    return arches.map { TargetSpec(os, it) }
}

/** One artifact as a catalogue states it: the target, the URL, the checksum. */
internal data class Download(
    val target: TargetSpec,
    val url: String,
    val sha256: String? = null,
)

private const val SHA256_HEX = 64

/**
 * Reduce a catalogue's downloads to a [Converted], or say why not.
 *
 * The order of the checks is the order of the messages a user should see: first whether this is GitHub Releases at
 * all — the hard rule, all or nothing — then whether it is one release, then whether ketch can unpack what was
 * published.
 */
internal fun assemble(
    backend: Backend,
    packageId: String,
    name: String,
    version: String,
    kind: PackageKind,
    bins: List<BinSpec>,
    downloads: List<Download>,
): Conversion {
    val parsed = downloads.mapNotNull { d -> releaseUrl(d.url)?.let { d to it } }
    val repo = parsed.firstOrNull()?.second?.repo
    // A mixed set — one architecture from GitHub, another from a mirror or another repository — has no single
    // `source` to write.
    if (repo == null || parsed.size != downloads.size || parsed.any { !it.second.repo.eqIgnoreAsciiCase(repo) }) {
        return Rejected.NotGithubReleases
    }
    val refusal = severalReleases(repo, parsed) ?: unpackable(parsed)
    return refusal ?: Converted(backend, packageId, sanitizeComponent(normalizeName(name)), version, repo, parsed[0].second.tag, kind, bins, artifactsOf(parsed))
}

private fun severalReleases(
    repo: String,
    parsed: List<Pair<Download, ReleaseUrl>>,
): Rejected? {
    val tags = parsed.map { it.second.tag }.distinct()
    if (tags.size == 1) return null
    val named = tags.map { it ?: "latest" }.distinct().sortedWith(BYTE_ORDER)
    return Rejected.Unsupported("its downloads come from several releases of $repo (${named.joinToString(", ")})")
}

/** The first download ketch could not install, in the order the catalogue lists them: a foreign package, or a second file for one target. */
private fun unpackable(parsed: List<Pair<Download, ReleaseUrl>>): Rejected? {
    val seen = mutableMapOf<String, String>()
    return parsed.firstNotNullOfOrNull { (download, url) ->
        val key = download.target.toString()
        val earlier = seen.getOrPut(key) { url.file }
        val format = foreignPackage(url.file)
        when {
            format != null -> {
                Rejected.Unsupported("it ships `${url.file}`, a `$format` package ketch can't unpack yet")
            }

            earlier != url.file -> {
                Rejected.Unsupported("it offers several downloads for $key ($earlier, ${url.file}) and ketch can't tell which one it means")
            }

            else -> {
                null
            }
        }
    }
}

/** One artifact per target, the first the catalogue lists, in byte order of the target. */
private fun artifactsOf(parsed: List<Pair<Download, ReleaseUrl>>): Map<String, Artifact> {
    val artifacts = mutableMapOf<String, Artifact>()
    for ((download, url) in parsed) {
        val sha256 =
            download.sha256
                ?.rustTrim()
                ?.asciiLowercase()
                ?.takeIf { s -> s.length == SHA256_HEX && s.all { it in '0'..'9' || it in 'a'..'f' } }
        artifacts.getOrPut(download.target.toString()) { Artifact(url.file, sha256) }
    }
    return artifacts.keys.sortedWith(BYTE_ORDER).associateWith { artifacts.getValue(it) }
}

/**
 * `name` (and a `path` only when the file is called something else), for one executable a catalogue links.
 *
 * The path is written as `*<file name>`: `*` matches across directories, so the entry survives both the wrapper
 * directory ketch unwraps and the version a release puts in that directory's name.
 */
internal fun binEntry(
    command: String,
    fileInPayload: String?,
): BinSpec? {
    val name = lastSegment(stripExe(command.rustTrim()))
    if (name.isEmpty()) return null
    val path =
        fileInPayload
            ?.let(::lastSegment)
            ?.takeIf { it.isNotEmpty() && !stripExe(it).eqIgnoreAsciiCase(name) }
            ?.let { "*$it" }
    return BinSpec(path = path, name = name)
}

private fun lastSegment(path: String) = path.substring(path.indexOfLast { it == '/' || it == '\\' } + 1)

private const val EXE = ".exe"

private fun stripExe(name: String) = if (name.length > EXE.length && name.takeLast(EXE.length).eqIgnoreAsciiCase(EXE)) name.dropLast(EXE.length) else name

/** What every file `ketch import` writes starts with. */
const val IMPORT_HEADER = "# Written by `ketch import"

private const val MIN_VERSION = 3

/**
 * A per-target asset pattern: the file name with its version replaced by `*`, so the pin keeps matching when
 * `ketch upgrade` moves to the next release. A name with no version in it stays exact.
 */
internal fun assetGlob(
    file: String,
    converted: Converted,
): String {
    val candidates = mutableListOf<String>()

    fun push(raw: String) {
        val s = raw.rustTrim()
        // Two characters is too little to be a version rather than part of a word: `v1` would also eat the `1` out
        // of `x86_64`… almost.
        if (s.length >= MIN_VERSION && s.any { it in '0'..'9' }) candidates += s
    }
    converted.tag?.let { tag ->
        push(tag)
        push(tag.trimStart('v', 'V'))
        push(numericCore(tag))
    }
    push(converted.version)
    push(converted.version.replace(',', '-'))
    push(numericCore(converted.version))
    var glob = file
    for (candidate in candidates.sortedByDescending { it.length }.distinct()) glob = glob.replace(candidate, "*")
    while ("**" in glob) glob = glob.replace("**", "*")
    return if (glob.all { it == '*' || it == '.' }) file else glob
}

/** The longest run of digits and dots in [text] that starts and ends with a digit: `0.160.0` from `rust-v0.160.0`. */
private fun numericCore(text: String): String =
    Regex("[0-9.]+")
        .findAll(text)
        .map { it.value.trim('.') }
        .fold("") { best, run -> if (run.length > best.length) run else best }
