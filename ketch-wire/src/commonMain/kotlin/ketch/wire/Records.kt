package ketch.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The plain records `ketch serve` hands the desktop app, the port of `ketch-ffi`'s `records.rs`. Paths and versions
 * cross as strings, field names are the Rust ones in snake case, and an absent value is an explicit null, so the
 * contract scenarios the Rust tree generates decode into them unchanged. Text a package's author wrote is sanitized
 * by the binary before it gets here.
 */

/** One installed package. */
@Serializable
data class Package(
    val name: String,
    val version: String,
    /** The release tag the version came from. */
    val tag: String,
    /** Where it came from, as `scheme:id` (`github:BurntSushi/ripgrep`). */
    val source: String,
    val pinned: Boolean,
    /** Older versions still on disk, oldest first. */
    val retained: List<String>,
    /** Seconds since the Unix epoch. */
    @SerialName("installed_at") val installedAt: Long,
    /** The store directory holding the payload. */
    val prefix: String,
    /** The links on `PATH` (or in `/Applications`) that expose it. */
    val binaries: List<String>,
    /** How far the download is established: `signed`, `checksum` or `first use`. */
    val trust: String,
)

/** What an install or upgrade placed. */
@Serializable
data class Installed(
    @SerialName("package") val pkg: Package,
    /** The version it replaced, for an upgrade or a reinstall. */
    val replaced: String?,
)

/**
 * How to install. The defaults are `ketch install` with no flags, except that an installed package with a newer
 * release is updated rather than asked about: the person already chose to install it in the front end.
 */
@Serializable
data class InstallOptions(
    /** Reinstall even when the resolved version is already present. */
    val force: Boolean = false,
    /** Allow a prerelease to be the latest. */
    val prerelease: Boolean = false,
    /** Link the binaries onto `PATH` (and `.app`s into Applications). */
    val link: Boolean = true,
    /** Fail rather than trust a download no checksum was published for. */
    @SerialName("require_checksum") val requireChecksum: Boolean = false,
    /** Consent to replace a checksummed install with a release that publishes none, as `--allow-unverified` gives. */
    @SerialName("allow_unverified") val allowUnverified: Boolean = false,
    /** Which of several binaries sharing the package's name to link. */
    val bin: String? = null,
)

/** A package the registry, or the person's own manifests, know. */
@Serializable
data class RegistryPackage(
    val name: String,
    val source: String,
    val description: String?,
    /** The newest version an earlier listing found, null when no fresh answer is cached. */
    val latest: String?,
    /** The manifest says this is an app; most say nothing, and a tool is the safer guess for those. */
    @SerialName("is_app") val isApp: Boolean = false,
)

/** A repository a source found for a search, installable by [spec] (`github:owner/repo`). */
@Serializable
data class Repository(
    val spec: String,
    val stars: Long?,
    val description: String?,
)

/** What a search found: curated packages first, then repositories. */
@Serializable
data class SearchResults(
    val known: List<RegistryPackage>,
    val repositories: List<Repository>,
)

/** An installed package with a newer release. */
@Serializable
data class Upgrade(
    val name: String,
    val installed: String,
    val latest: String,
    /** The release tag `upgrade` installs. */
    val tag: String,
    /** Held at [installed] by a pin: `upgrade` leaves it alone until it is unpinned. */
    val pinned: Boolean,
    /** The `ketch.lock` that holds the pin; `ketch sync` does not record it, so it is always null today. */
    @SerialName("held_by") val heldBy: String?,
)

/** Where a changelog came from. */
@Serializable
sealed interface ChangelogSource {
    /** A file inside the installed payload. */
    @Serializable
    @SerialName("file")
    data class File(
        val path: String,
    ) : ChangelogSource

    /** Notes published with the release. */
    @Serializable
    @SerialName("release")
    data object Release : ChangelogSource
}

/** What changed in one release of a package. */
@Serializable
data class Changelog(
    val name: String,
    val version: String,
    val source: ChangelogSource,
    /** The heading the section was found under; null from a file means no section matched and [body] is the file. */
    val heading: String?,
    val body: String,
)

/** How a doctor check came out. */
@Serializable
enum class CheckOutcome {
    @SerialName("ok")
    Ok,

    @SerialName("warn")
    Warn,

    @SerialName("fail")
    Fail,
}

/** One line of `ketch doctor`. */
@Serializable
data class Check(
    val name: String,
    val outcome: CheckOutcome,
    val detail: String,
    /** What to do about it, when there is something to do. */
    val fix: String?,
    /** [Call.DoctorFix] repairs this one. */
    val fixable: Boolean = false,
)

/** One thing that happened to a package, as `stats.db` recorded it. */
@Serializable
data class HistoryEvent(
    @SerialName("package") val pkg: String,
    /** `install`, `upgrade`, `uninstall`, `rollback` and the rest. */
    val action: String,
    val version: String,
    /** The version it replaced, for an upgrade or a rollback. */
    @SerialName("previous_version") val previousVersion: String?,
    val tag: String,
    val source: String,
    /** Seconds since the Unix epoch. */
    val at: Long,
    @SerialName("checksum_verified") val checksumVerified: Boolean,
    @SerialName("duration_ms") val durationMs: Int?,
)

/** What `ketch info` says about a package. */
@Serializable
data class PackageInfo(
    val name: String,
    /** Where it comes from, as `scheme:id`. */
    val source: String,
    /** The project's page on its forge. */
    val url: String?,
    val description: String?,
    val homepage: String?,
    val stars: Long?,
    val license: String?,
    /** The repository is archived: nobody maintains it any more. */
    val archived: Boolean,
    /** The newest release, null when the source could not say. */
    val latest: String?,
    @SerialName("latest_tag") val latestTag: String?,
    /** The installed record, when it is installed. */
    val installed: Package?,
)

/** The retained versions `prune` removed from one package. */
@Serializable
data class Pruned(
    val name: String,
    val versions: List<String>,
)

/** Where one shell stands with ketch's PATH block. */
@Serializable
enum class ShellState {
    /** Its startup file has the block for this bin dir. */
    @SerialName("configured")
    Configured,

    /** It is in use here and not set up yet. */
    @SerialName("not_set_up")
    NotSetUp,

    /** Nothing says it is used on this machine. */
    @SerialName("not_in_use")
    NotInUse,
}

/** One shell's row in [PathStatus]. */
@Serializable
data class ShellSetup(
    /** `bash`, `zsh` or `fish`. */
    val shell: String,
    val state: ShellState,
    /** The startup file `path_install` would edit. */
    val file: String,
)

/** Whether the bin dir is on `PATH`, and where it is or could be set up: what `ketch path` shows. */
@Serializable
data class PathStatus(
    @SerialName("bin_dir") val binDir: String,
    /** On `PATH` in the environment `ketch serve` was started with. */
    @SerialName("on_path") val onPath: Boolean,
    /** The doctor's PATH check, in words. */
    val detail: String,
    /** Whether the Windows user PATH names the bin dir; null off Windows. */
    @SerialName("user_path") val userPath: Boolean?,
    val shells: List<ShellSetup>,
)

/** What a PATH setup step did. */
@Serializable
enum class PathOutcome {
    /** The block was written for the first time. */
    @SerialName("added")
    Added,

    /** A block naming another directory was rewritten. */
    @SerialName("updated")
    Updated,

    /** The block was taken out. */
    @SerialName("removed")
    Removed,

    /** Nothing to do: already right, or set up by hand. */
    @SerialName("unchanged")
    Unchanged,
}

/** How `ketch install` would choose for a package, from the same resolver: what `ketch why` prints. */
@Serializable
data class Resolution(
    @SerialName("package") val pkg: String,
    /** `scheme:id`, the source that answers for the package. */
    val source: String,
    /** The release that won, null when the source has none. */
    val version: String?,
    /** The asset that would be installed, null when none fits this machine. */
    val asset: String?,
    /** Why that asset won. */
    val reason: String?,
    /** What install would do about the checksum. Nothing has been downloaded or checked yet. */
    val checksum: String,
)

/** One place `path_install` or `doctor_fix` set up, or would set up. */
@Serializable
data class PathChange(
    /** The shell's name, or `user PATH` for the Windows user environment. */
    val target: String,
    /** The startup file, for a shell. */
    val file: String?,
    val outcome: PathOutcome,
)

/** ketch's effective configuration: `config.toml` and the environment over the defaults, as the next call sees it. */
@Serializable
data class Settings(
    val root: String,
    @SerialName("bin_dir") val binDir: String,
    @SerialName("store_dir") val storeDir: String,
    /** Where `.app` bundles are linked. */
    @SerialName("apps_dir") val appsDir: String,
    @SerialName("config_file") val configFile: String,
    @SerialName("log_file") val logFile: String,
    /** `owner/repo` of the package registry. */
    val registry: String,
    /** The target releases are picked for, such as `aarch64-apple-darwin`. */
    val target: String,
    val jobs: Int,
    val prerelease: Boolean,
    @SerialName("allow_emulation") val allowEmulation: Boolean,
    @SerialName("link_apps") val linkApps: Boolean,
    @SerialName("require_checksums") val requireChecksums: Boolean,
    @SerialName("strip_quarantine") val stripQuarantine: Boolean,
    @SerialName("auto_update") val autoUpdate: Boolean,
    /** Whether a GitHub token is set. The token itself never crosses. */
    @SerialName("github_token") val githubToken: Boolean,
    @SerialName("log_level") val logLevel: String,
)
