package ketch.core.listing

import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.install.InstalledBuild
import ketch.core.install.isUpgrade
import ketch.core.install.releaseOf
import ketch.core.install.upgradeOpts
import ketch.core.manifest.sameSource
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.model.PackageRef
import ketch.core.model.Version
import ketch.core.model.normalizeName
import ketch.core.report.Report
import ketch.core.source.ListOpts
import ketch.core.text.codePoints
import ketch.core.text.splitWhitespace
import ketch.core.text.truncate

/*
 * What `ketch list` shows: installed packages, the registry, and both merged.
 *
 * Separate from the command because the merge has rules of its own — a pinned package is never an update, a source
 * that cannot be reached is a `?` rather than a failed command, one release lookup serves every row that shares a
 * source — and those rules want unit tests with no binary in the way. The lookup is `install.releaseOf`, the call
 * behind `ketch outdated`, and whether its answer is an update is `install.isUpgrade`, the rule `outdated` applies.
 */

/** What the `installed` column says after the version when the update is held. */
const val PINNED = "(pinned)"

/** What the `latest` column says after the version when it is an update. */
const val UPDATE = "(update available)"

/** What the `latest` column says when the source could not be reached. */
const val UNKNOWN = "?"

/** An installed package, as much of it as a listing needs. */
data class Local(
    val name: String,
    val source: PackageRef,
    /** What is installed, as the update rule compares it: version, tag, channel and build. */
    val build: InstalledBuild,
    val pinned: Boolean,
    val retained: List<Version>,
    /** Whether `latest` may be a prerelease, decided as `ketch outdated` does. */
    val prerelease: Boolean,
    /** Whether `latest` may be a draft: a `draft` install, or `list --draft`. */
    val draft: Boolean,
) {
    internal fun listOpts() = ListOpts(includePrerelease = prerelease, includeDraft = draft)

    /**
     * The `installed` cell: the version and the notes `ketch list local` has always carried. A channel other than
     * stable follows the version, since it says where the next version will come from.
     */
    fun cell(): String =
        buildString {
            append(build.version)
            if (build.channel != Channel.Stable) append(" (${build.channel.asStr})")
            if (pinned) append(" $PINNED")
            if (retained.isNotEmpty()) append(" (+${retained.size} retained)")
        }

    companion object {
        fun fromInstalled(
            cfg: Config,
            pkg: InstalledPackage,
        ): Local {
            val opts = upgradeOpts(pkg, cfg.prerelease, false)
            return Local(
                name = pkg.name,
                source = pkg.source,
                build = InstalledBuild.of(pkg),
                pinned = pkg.pinned,
                retained = pkg.retained.map { it.version },
                prerelease = opts.includePrerelease,
                draft = opts.includeDraft,
            )
        }
    }
}

/** A package the registry offers. */
data class Available(
    val name: String,
    val source: PackageRef,
    val description: String?,
    /** Whether `latest` may be a prerelease, decided as install does. */
    val prerelease: Boolean,
) {
    companion object {
        fun fromManifest(
            cfg: Config,
            manifest: Manifest,
        ) = Available(
            name = manifest.name,
            source = manifest.source,
            description = manifest.description,
            prerelease =
                ketch.core.resolve
                    .listOpts(cfg, manifest, false)
                    .includePrerelease,
        )
    }
}

/** The `latest` of one row. */
sealed interface Latest {
    /** Not looked up: a package installed from a local path has no upstream, and a listing that needs no versions never asks. */
    data object NotChecked : Latest

    data class Known(
        val found: Found,
    ) : Latest

    /** The source was asked and did not answer. */
    data object Unreachable : Latest
}

/** One source asked for its newest release — with or without prereleases and drafts — or, for a package on a floating tag, for that tag again. */
data class Lookup(
    val source: PackageRef,
    val prerelease: Boolean,
    val draft: Boolean,
    /** The floating tag a `dev` install follows. */
    val tag: String?,
) {
    /**
     * The cache key. Everything that changes the answer is part of it: the same repository has a different `latest`
     * for someone who opted into prereleases or drafts, and a floating tag is a question of its own.
     */
    fun key(): String {
        val channel =
            when {
                draft -> "draft"
                prerelease -> "pre"
                else -> "stable"
            }
        return if (tag != null) "$source@$tag#dev" else "$source#$channel"
    }

    internal fun opts() = ListOpts(includePrerelease = prerelease, includeDraft = draft)
}

/** One row of a listing: installed, available, or both. */
class Row(
    val name: String,
    var local: Local?,
    var available: Available?,
    var latest: Latest = Latest.NotChecked,
) {
    /** Where the package comes from: what it was installed from when it is installed, since that is where its next version will come from too. */
    fun source(): PackageRef? = local?.source ?: available?.source

    /** The lookup that answers this row's `latest`, if it has one. */
    fun lookup(): Lookup? {
        val l = local
        val a = available
        val lookup =
            when {
                l != null -> Lookup(l.source, l.prerelease, l.draft, l.build.followedTag())
                a != null -> Lookup(a.source, a.prerelease, false, null)
                else -> null
            }
        // A local path has no release stream, and its synthetic tag is not an upstream to compare against: `ketch
        // outdated` skips it for the same reason.
        return lookup?.takeIf { it.source.scheme != "local" }
    }

    /** Whether `latest` should replace what is installed, the pin aside: the rule `ketch outdated` applies. [report] hears a draft that is gone. */
    fun newer(report: Report): Boolean {
        val l = local ?: return false
        val known = latest as? Latest.Known
        return known != null && isUpgrade(report, l.build, known.found.release, l.listOpts())
    }

    /** Whether the row offers an update: a newer release, and no pin holding it back. */
    fun updateAvailable(): Boolean = local?.pinned == false && newer(Report.silent())

    /** The `latest` cell: a version, a version marked as an update, `?`, or nothing when there was nothing to ask. */
    fun latestCell(): String =
        when (val l = latest) {
            is Latest.Known -> {
                if (updateAvailable()) {
                    "${l.found.release.version} $UPDATE"
                } else {
                    l.found.release.version
                        .toString()
                }
            }

            Latest.Unreachable -> {
                UNKNOWN
            }

            Latest.NotChecked -> {
                ""
            }
        }

    fun latestVersion(): String? =
        (latest as? Latest.Known)
            ?.found
            ?.release
            ?.version
            ?.toString()

    companion object {
        fun installed(local: Local) = Row(local.name, local, null)

        fun offered(available: Available) = Row(available.name, null, available)
    }
}

/**
 * Let every row's `latest` be a prerelease: `ketch list --pre`. Applied to the rows rather than to the config because
 * a row is what decides its lookup and its cache key, and the flag is part of both already.
 */
fun includePrereleases(rows: List<Row>) {
    for (row in rows) {
        row.local = row.local?.copy(prerelease = true)
        row.available = row.available?.copy(prerelease = true)
    }
}

/**
 * Let installed rows' `latest` be a draft: `ketch list --draft`. Installed rows only: a draft is visible only with
 * push access to the repository, which a registry listing of other people's packages would nearly always lack,
 * turning every row into a `?`.
 */
fun includeDrafts(rows: List<Row>) {
    for (row in rows) row.local = row.local?.copy(draft = true, prerelease = true)
}

/**
 * Installed and available packages as one list, one row per package, sorted by name.
 *
 * An installed package and a registry entry are the same package when their names match, or failing that when they
 * name the same source — installing `BurntSushi/ripgrep` records the curated name, but a user manifest may have
 * renamed it since.
 */
fun merge(
    locals: List<Local>,
    available: List<Available>,
): List<Row> {
    val installed = locals.map(Row::installed)
    val rows = installed.toMutableList()
    for (offer in available) {
        val wanted = normalizeName(offer.name)
        // Offered-only rows can never match; scanning the growing output makes a registry-only listing quadratic.
        val match =
            installed.firstOrNull { it.available == null && normalizeName(it.local!!.name) == wanted }
                ?: installed.firstOrNull { it.available == null && sameSource(it.local!!.source, offer.source) }
        if (match != null) match.available = offer else rows += Row.offered(offer)
    }
    return sort(rows)
}

/** Rows the way every listing prints them: by name, case-insensitively. */
fun sort(rows: List<Row>): List<Row> = rows.sortedWith(compareBy<Row> { normalizeName(it.name) }.thenBy { it.name })

/** How the lookups went. */
data class Outcome(
    /** Distinct sources that needed a `latest`. */
    val asked: Int = 0,
    /** Of those, how many have one, fresh from the source or from the cache. */
    val answered: Int = 0,
) {
    /** Nothing answered at all: the network, not one package, is missing. */
    fun offline() = asked > 0 && answered == 0
}

/** The line under the table naming each package that has an update, with the command that takes them all. */
fun updateFooter(rows: List<Row>): String? {
    val names = rows.filter { it.updateAvailable() }.map { it.name }
    return when (names.size) {
        0 -> null
        1 -> "1 update available: ketch upgrade ${names[0]}"
        else -> "${names.size} updates available: ketch upgrade ${names.joinToString(" ")}"
    }
}

/** The line under the table naming each package whose `latest` is `?`. */
fun unreachableNote(rows: List<Row>): String? = unreachable(rows).takeIf { it.isNotEmpty() }?.let { "$UNKNOWN means the latest release could not be checked: ${it.joinToString(", ")}" }

/** Names of the rows whose source did not answer. */
fun unreachable(rows: List<Row>): List<String> = rows.filter { it.latest == Latest.Unreachable }.map { it.name }

/** Below a dozen columns a description says nothing, so a very narrow terminal wraps rather than shows `…`. */
private const val MIN_DESCRIPTION = 12

/** Two separators of two spaces each. */
private const val SEPARATORS = 4

/**
 * Rows for `ketch list remote`: `package`, `latest`, `description`, with each description cut so the row fits
 * [width] columns when there is one. A description is somebody else's prose, so it is filtered and folded onto one
 * line before it is measured; the table filters again, which is then a no-op.
 */
fun remoteCells(
    rows: List<Row>,
    width: Int?,
): List<List<String>> {
    val cells =
        rows.map { row ->
            val description = row.available?.description?.let { d -> sanitize(d).splitWhitespace().joinToString(" ") }
            Triple(sanitize(row.name), row.latestCell(), description.orEmpty())
        }
    val nameWidth = (cells.map { charCount(it.first) } + "package".length).max()
    val latestWidth = (cells.map { charCount(it.second) } + "latest".length).max()
    val room = width?.let { (it - nameWidth - latestWidth - SEPARATORS).coerceAtLeast(MIN_DESCRIPTION) }
    return cells.map { (name, latest, description) -> listOf(name, latest, room?.let { truncate(description, it) } ?: description) }
}

/** Unicode scalar values, as Rust's `chars().count()`. */
private fun charCount(s: String) = codePoints(s).size
