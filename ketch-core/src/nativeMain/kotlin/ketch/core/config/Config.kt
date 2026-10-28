package ketch.core.config

import fs.FsException
import fs.atomic
import fs.createDirAll
import fs.entryKind
import fs.readText
import ketch.core.VERSION
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.log.Format
import ketch.core.log.Level
import ketch.core.model.Os
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.report.Report
import ketch.core.text.asciiLowercase
import ketch.core.text.rustTrim
import ketch.core.tomlfile.parse
import ketch.core.tomlfile.render
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val USER_AGENT = "ketch/$VERSION"

/**
 * The file `ketch self uninstall` looks for before it deletes a root: `KETCH_ROOT=$HOME` must never make it delete
 * the home directory. Only a root ketch created gets one.
 */
const val ROOT_MARKER = ".ketch-root"

private const val ROOT_MARKER_BODY =
    "ketch created this directory; `ketch self uninstall` removes it with everything inside.\n"

/** The largest download, in MiB, unless `max_download_mib` says otherwise. */
const val DEFAULT_MAX_DOWNLOAD_MIB = 4096uL

private const val DEFAULT_JOBS = 4uL
private const val MAX_JOBS = 16uL
private const val MIB = 1_048_576uL

/** `config.toml` as written. Every key is optional; an unknown one is refused, as a typo would be silently lost. */
@Serializable
data class ConfigFile(
    val root: String? = null,
    @SerialName("apps_dir") val appsDir: String? = null,
    @SerialName("github_token") val githubToken: String? = null,
    val prerelease: Boolean? = null,
    @SerialName("allow_emulation") val allowEmulation: Boolean? = null,
    @SerialName("link_apps") val linkApps: Boolean? = null,
    @SerialName("require_checksums") val requireChecksums: Boolean? = null,
    @SerialName("strip_quarantine") val stripQuarantine: Boolean? = null,
    @SerialName("auto_update") val autoUpdate: Boolean? = null,
    val emoji: Boolean? = null,
    @SerialName("self_repo") val selfRepo: String? = null,
    val registry: String? = null,
    val jobs: ULong? = null,
    @SerialName("log_level") val logLevel: Level? = null,
    @SerialName("log_format") val logFormat: Format? = null,
    @SerialName("max_download_mib") val maxDownloadMib: ULong? = null,
)

/** Where everything lives and how ketch behaves, resolved from the defaults, `config.toml` and the environment. */
data class Config(
    val root: Path,
    val binDir: Path,
    val storeDir: Path,
    val cacheDir: Path,
    val manifestDir: Path,
    val pluginDir: Path,
    val stateFile: Path,
    val statsDb: Path,
    val lockFile: Path,
    val configFile: Path,
    val appsDir: Path,
    val githubToken: String?,
    val prerelease: Boolean,
    val allowEmulation: Boolean,
    val linkApps: Boolean,
    val requireChecksums: Boolean,
    val stripQuarantine: Boolean,
    val autoUpdate: Boolean,
    val emoji: Boolean,
    val selfRepo: String,
    val registry: String,
    val registryDir: Path,
    val registryMeta: Path,
    val target: TargetSpec,
    val jobs: Int,
    val logFile: Path,
    val logLevel: Level,
    val logFormat: Format,
    val maxDownload: ULong,
) {
    /** Create the directories ketch writes into; a root this call creates gets [ROOT_MARKER]. */
    fun ensureDirs() {
        val fresh = entryKind(root) == null
        for (dir in listOf(root, binDir, storeDir, cacheDir, manifestDir, pluginDir)) {
            io(dir) { createDirAll(dir) }
        }
        if (fresh) markRoot()
    }

    fun rootMarker() = Path(root, ROOT_MARKER)

    fun markRoot() {
        val marker = rootMarker()
        io(marker) { atomic(marker, ROOT_MARKER_BODY.encodeToByteArray()) }
    }

    fun packageDir(
        name: String,
        version: String,
    ) = Path(Path(storeDir, name), sanitizeComponent(version))

    /** Whether [binDir] is on `PATH`, comparing entries as the shell would find them. */
    fun binDirOnPath(): Boolean {
        val path = envOs("PATH") ?: return false
        val want = pathLookupKey(binDir.toString())
        return splitPathVar(path).any { pathLookupKey(it) == want }
    }

    companion object {
        fun load(
            rootOverride: Path?,
            report: Report,
        ): Config {
            val root = resolveRoot(rootOverride)
            val configFile = Path(root, "config.toml")
            val file = readFile(configFile)

            val appsDir = appsDir(root, file)

            // The file lives inside the root, so it cannot choose it; saying so beats ignoring the key silently.
            if (file.root != null) report.warn("`root` in $configFile has no effect; set KETCH_ROOT or pass --root")

            val selfRepo = repo("self_repo", "KETCH_SELF_REPO", file.selfRepo, SELF_REPO)
            val registry = repo("registry", "KETCH_REGISTRY", file.registry, REGISTRY_REPO)
            val githubToken = githubToken(file)
            // A parse failure here is the user's own config or environment, so it is an error, not a fall back.
            val logLevel = fromEnvOr("KETCH_LOG_LEVEL", file.logLevel, Level::parse) ?: Level.DEFAULT
            val logFormat = fromEnvOr("KETCH_LOG_FORMAT", file.logFormat, Format::parse) ?: Format.DEFAULT
            val jobs = jobs(file)
            val maxDownload = maxDownload(file)

            return Config(
                root = root,
                binDir = Path(root, "bin"),
                storeDir = Path(root, "store"),
                cacheDir = Path(root, "cache"),
                manifestDir = Path(root, "manifests"),
                pluginDir = Path(root, "plugins"),
                stateFile = Path(root, "state.json"),
                statsDb = Path(root, "stats.db"),
                lockFile = Path(root, ".lock"),
                configFile = configFile,
                appsDir = appsDir,
                githubToken = githubToken,
                prerelease = flag("KETCH_PRERELEASE", file.prerelease, false),
                allowEmulation = flag("KETCH_ALLOW_EMULATION", file.allowEmulation, true),
                linkApps = flag("KETCH_LINK_APPS", file.linkApps, false),
                requireChecksums = flag("KETCH_REQUIRE_CHECKSUMS", file.requireChecksums, false),
                stripQuarantine = flag("KETCH_STRIP_QUARANTINE", file.stripQuarantine, true),
                autoUpdate = flag("KETCH_AUTO_UPDATE", file.autoUpdate, true),
                emoji = flag("KETCH_EMOJI", file.emoji, true),
                selfRepo = selfRepo,
                registry = registry,
                // Deliberately not in `ensureDirs`: the directory existing is how ketch knows the registry has
                // been fetched.
                registryDir = Path(root, "registry"),
                registryMeta = Path(root, "registry.meta.toml"),
                target = TargetSpec.host(),
                jobs = jobs,
                logFile = Path(Path(root, "logs"), "ketch.log"),
                logLevel = logLevel,
                logFormat = logFormat,
                maxDownload = maxDownload,
            )
        }

        /** What `ketch config reset` writes: every setting at its compiled default, spelled out. */
        fun defaultToml(): String {
            val file =
                ConfigFile(
                    prerelease = false,
                    allowEmulation = true,
                    linkApps = false,
                    requireChecksums = false,
                    stripQuarantine = true,
                    autoUpdate = true,
                    emoji = true,
                    selfRepo = SELF_REPO,
                    registry = REGISTRY_REPO,
                    jobs = DEFAULT_JOBS,
                    logLevel = Level.DEFAULT,
                    logFormat = Format.DEFAULT,
                    maxDownloadMib = DEFAULT_MAX_DOWNLOAD_MIB,
                )
            return "# Written by `ketch config reset`. Edit freely.\n" + render(file, "config.toml")
        }

        private fun readFile(path: Path): ConfigFile {
            val isFile = SystemFileSystem.metadataOrNull(path)?.isRegularFile == true
            if (!isFile) return ConfigFile()
            val text = io(path) { readText(path) }
            return parse<ConfigFile>(text, path.toString())
        }

        /**
         * A variable that is set but empty means "unset", as for every setting here: `KETCH_ROOT=` is what a CI job
         * writes when it clears a variable, and reading it literally makes the working directory the root and fills
         * it with store/, bin/ and cache/.
         */
        private fun resolveRoot(rootOverride: Path?): Path {
            val given = rootOverride ?: envOs("KETCH_ROOT")?.takeIf { it.isNotEmpty() }?.let { Path(it) }
            return given?.let { absolutePath(expandTilde(it)) } ?: defaultRoot()
        }

        /** Environment over file: the file is the standing preference, the variable this run's override. */
        private fun appsDir(
            root: Path,
            file: ConfigFile,
        ): Path {
            val given = envOs("KETCH_APPS_DIR")?.takeIf { it.isNotEmpty() } ?: file.appsDir
            val dir =
                given?.let { expandTilde(Path(it)) }
                    ?: if (TargetSpec.host().os == Os.MacOs) Path("/Applications") else Path(root, "apps")
            // A relative apps dir would resolve against wherever ketch was run from, a different place each time.
            if (!dir.isAbsolute) throw KetchError.Config("apps_dir must be an absolute path, not `$dir`")
            return dir
        }

        /** Each variable is filtered before the next is tried: `KETCH_GITHUB_TOKEN=` is how CI clears a secret. */
        private fun githubToken(file: ConfigFile): String? =
            listOf("KETCH_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN").firstNotNullOfOrNull(::nonBlankEnv)
                ?: file.githubToken?.takeIf { it.rustTrim().isNotEmpty() }

        /**
         * Downloads spend their time waiting, so the useful number is well above the core count. Capped anyway: a
         * hundred parallel requests is how a source starts refusing them.
         */
        private fun jobs(file: ConfigFile): Int {
            val jobs = wholeEnv("KETCH_JOBS") ?: file.jobs
            return (jobs?.takeIf { it > 0uL } ?: DEFAULT_JOBS).coerceAtMost(MAX_JOBS).toInt()
        }

        private fun maxDownload(file: ConfigFile): ULong {
            val mib = wholeEnv("KETCH_MAX_DOWNLOAD_MIB") ?: file.maxDownloadMib
            // Zero would refuse every download; that is a typo, not a policy.
            if (mib == 0uL) throw KetchError.Config("max_download_mib must be at least 1")
            return saturatingMul(mib ?: DEFAULT_MAX_DOWNLOAD_MIB, MIB)
        }

        private fun flag(
            key: String,
            fromFile: Boolean?,
            default: Boolean,
        ): Boolean = envBool(key) ?: fromFile ?: default

        private fun repo(
            what: String,
            key: String,
            fromFile: String?,
            default: String,
        ): String = currentRepo(validateRepo(what, nonBlankEnv(key) ?: fromFile ?: default))
    }
}

private inline fun <T> io(
    path: Path,
    body: () -> T,
): T =
    try {
        body()
    } catch (e: FsException) {
        throw KetchError.fsKit(path, e)
    }

private fun saturatingMul(
    a: ULong,
    b: ULong,
): ULong = if (a != 0uL && b > ULong.MAX_VALUE / a) ULong.MAX_VALUE else a * b

private fun nonBlankEnv(key: String): String? = env(key)?.takeIf { it.rustTrim().isNotEmpty() }

private fun wholeEnv(key: String): ULong? =
    nonBlankEnv(key)?.let { v ->
        parseUnsigned(v.rustTrim()) ?: throw KetchError.Config("$key must be a whole number, not `$v`")
    }

/** Rust `str::parse::<u64>`: digits with an optional leading `+`, nothing else. */
private fun parseUnsigned(text: String): ULong? = text.removePrefix("+").takeIf { d -> d.isNotEmpty() && d.all { it in '0'..'9' } }?.toULongOrNull()

private fun <T> fromEnvOr(
    key: String,
    fromFile: T?,
    parse: (String) -> T,
): T? {
    val v = nonBlankEnv(key) ?: return fromFile
    return try {
        parse(v)
    } catch (
        // The message is the whole of what went wrong; KetchError.Config carries text only, as Rust's does.
        @Suppress("SwallowedException") e: IllegalArgumentException,
    ) {
        throw KetchError.Config("$key: ${e.message}")
    }
}

/** `1/true/yes/on` or `0/false/no/off`; empty is unset, so `KETCH_PRERELEASE=` clears the override. */
internal fun envBool(key: String): Boolean? {
    val value =
        when (val v = envVar(key)) {
            EnvVar.Unset -> return null
            EnvVar.NotUnicode -> throw KetchError.Config("$key: environment variable was not valid unicode")
            is EnvVar.Set -> v.value
        }
    return when (value.rustTrim().asciiLowercase()) {
        "" -> null
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> throw KetchError.Config("$key must be a boolean, not `$value`")
    }
}

private fun defaultRoot(): Path = Path(homeDir() ?: Path("."), ".ketch")

internal fun absolutePath(path: Path): Path {
    if (path.isAbsolute) return path
    val cwd = currentDir() ?: throw KetchError.Io(Path("."), IllegalStateException("the current directory is gone"))
    return Path(cwd, path.toString())
}

/**
 * `~` and `~/…` under the home directory. PowerShell and Windows config often write `~\.ketch`, so the rest is
 * joined by components split on either separator: `~\.ketch\bin` is two segments on every host.
 */
internal fun expandTilde(path: Path): Path {
    val text = path.toString()
    val home = homeDir() ?: return path
    val rest = text.removePrefix("~/").takeIf { it != text } ?: text.removePrefix("~\\").takeIf { it != text }
    return when {
        text == "~" -> home
        rest == null -> path
        else -> rest.split('/', '\\').filter { it.isNotEmpty() }.fold(home) { acc, part -> Path(acc, part) }
    }
}

/** The directories a `PATH`-style variable lists, in order, as Rust's `std::env::split_paths` reads them. */
internal fun splitPathVar(value: String): List<String> = if (HOST_WINDOWS) splitWindowsPathVar(value) else value.split(':')

/**
 * A Windows `PATH`: `"` quotes a `;` into an entry (`C:\weird;name` is a valid folder) and is dropped, since no Windows
 * name can hold one. Empty entries between separators stay, and an empty value lists nothing, as in Rust's std.
 */
internal fun splitWindowsPathVar(value: String): List<String> {
    val entries = mutableListOf<String>()
    val entry = StringBuilder()
    var quoted = false
    for (c in value) {
        when {
            c == '"' -> quoted = !quoted
            c == ';' && !quoted -> entries += entry.toString().also { entry.clear() }
            else -> entry.append(c)
        }
    }
    // The last entry counts when it has text or follows a separator.
    if (entry.isNotEmpty() || entries.isNotEmpty()) entries += entry.toString()
    return entries
}

/** A `PATH` entry as the shell matches it: either separator, no trailing one, case folded on Windows. */
internal fun pathLookupKey(p: String): String {
    val s = p.replace('\\', '/').trimEnd('/')
    return if (HOST_WINDOWS) s.asciiLowercase() else s
}
