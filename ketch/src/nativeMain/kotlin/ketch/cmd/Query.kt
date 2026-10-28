// Read-only commands, the listings: `list` and `outdated` from src/cmd/query.rs.
//
// These never take the lock and never write. Their data output goes to stdout through `out`/`table` so it can be piped
// while progress and warnings stay on stderr.
package ketch.cmd

import cli.Matches
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.install.InstalledBuild
import ketch.core.install.isUpgrade
import ketch.core.install.latestRelease
import ketch.core.install.upgradeOpts
import ketch.core.listing.Available
import ketch.core.listing.Local
import ketch.core.listing.Row
import ketch.core.listing.UPDATE
import ketch.core.listing.fillLatest
import ketch.core.listing.includeDrafts
import ketch.core.listing.includePrereleases
import ketch.core.listing.merge
import ketch.core.listing.remoteCells
import ketch.core.listing.unreachable
import ketch.core.listing.unreachableNote
import ketch.core.listing.updateFooter
import ketch.core.manifest.Resolver
import ketch.core.model.InstalledPackage
import ketch.core.model.Release
import ketch.core.source.SourceRegistry
import ketch.core.state.State
import ketch.ui.Tone
import ketch.ui.bold
import ketch.ui.colorEnabled
import ketch.ui.ctx
import ketch.ui.note
import ketch.ui.out
import ketch.ui.printable
import ketch.ui.report
import ketch.ui.stdoutWidth
import ketch.ui.step
import ketch.ui.table
import ketch.ui.tableStyled
import ketch.ui.tone
import ketch.ui.warn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What `ketch list` prints when there is no registry to list. */
private const val EMPTY_REGISTRY = "registry is empty; run ketch update"

/** The `PKG` every package query requires. */
internal fun Matches.pkg(): String = checkNotNull(value("package")) { "the command tree requires PKG" }

/** A count option the tree validated as a whole number; one past `Int.MAX_VALUE` asks for no more than that. */
internal fun Matches.count(id: String): Int = checkNotNull(value(id)).toULong().coerceAtMost(Int.MAX_VALUE.toULong()).toInt()

/**
 * `ketch list [local|remote]`: installed packages, the registry, or both in one table with the latest version of each.
 *
 * The merge, the lookups and their cache live in `listing`; this decides which of them a mode needs and prints the
 * answer. Only the modes that show `latest` touch the network, and `--names-only` never does.
 */
fun list(
    cfg: Config,
    args: Matches,
) {
    val mode =
        if (args.flag("installed")) {
            note("`--installed` is now `ketch list local`; the flag goes away in the next release")
            "local"
        } else {
            args.value("mode")
        }
    when (mode) {
        "local" -> listLocal(cfg, args)
        "remote" -> listRemote(cfg, args)
        else -> listAll(cfg, args)
    }
}

private fun printNames(rows: List<Row>) = rows.forEach { out(printable(it.name)) }

private fun listLocal(
    cfg: Config,
    args: Matches,
) {
    val rows = merge(State.load(cfg).iter().map { Local.fromInstalled(cfg, it) }, emptyList())
    when {
        args.flag("json") -> printJson(JsonArray(rows.mapNotNull { it.local }.map(::localJson)))
        args.flag("names_only") -> printNames(rows)
        else -> printLocalTable(rows)
    }
}

private fun localJson(local: Local) =
    jsonOf(
        "name" to JsonPrimitive(printable(local.name)),
        "installed" to JsonPrimitive(local.build.version.toString()),
        "channel" to JsonPrimitive(local.build.channel.asStr),
        "pinned" to JsonPrimitive(local.pinned),
        "retained" to JsonArray(local.retained.map { JsonPrimitive(it.toString()) }),
        "source" to JsonPrimitive(local.source.toString()),
    )

private fun printLocalTable(rows: List<Row>) {
    if (rows.isEmpty()) {
        out("nothing installed")
    } else {
        table(listOf("package", "installed", "source"), rows.mapNotNull { it.local }.map { listOf(it.name, it.cell(), it.source.toString()) })
    }
}

private fun listRemote(
    cfg: Config,
    args: Matches,
) {
    val resolver = Resolver.load(ctx(cfg))
    val rows = resolver.listed().map { Row.offered(Available.fromManifest(cfg, it)) }
    when {
        rows.isEmpty() && args.flag("json") -> printJson(JsonArray(emptyList()))
        rows.isEmpty() -> if (!args.flag("names_only")) out(EMPTY_REGISTRY)
        args.flag("names_only") -> printNames(rows)
        else -> showRemote(cfg, args, resolver, rows)
    }
}

private fun showRemote(
    cfg: Config,
    args: Matches,
    resolver: Resolver,
    rows: List<Row>,
) {
    // Only the user's own manifests are left to list: worth saying why the registry's packages are missing from it.
    if (resolver.registryIsEmpty()) note(EMPTY_REGISTRY)
    widen(args, rows)
    if (fillLatest(ctx(cfg), SourceRegistry.load(ctx(cfg)), rows).offline()) {
        throw KetchError.Msg("could not reach any package source to check the latest versions; `ketch list local` works offline")
    }
    if (args.flag("json")) {
        printJson(
            JsonArray(
                rows.map {
                    jsonOf(
                        "name" to JsonPrimitive(printable(it.name)),
                        "latest" to JsonPrimitive(it.latestVersion()),
                        "description" to JsonPrimitive(jsonProse(it.available?.description)),
                        "source" to JsonPrimitive(it.source()?.toString()),
                    )
                },
            ),
        )
    } else {
        table(listOf("package", "latest", "description"), remoteCells(rows, stdoutWidth()))
        unreachableNote(rows)?.let(::out)
    }
}

private fun widen(
    args: Matches,
    rows: List<Row>,
) {
    if (args.flag("prerelease")) includePrereleases(rows)
    if (args.flag("draft")) includeDrafts(rows)
}

private fun listAll(
    cfg: Config,
    args: Matches,
) {
    val state = State.load(cfg)
    val resolver = Resolver.load(ctx(cfg))
    val rows = merge(state.iter().map { Local.fromInstalled(cfg, it) }, resolver.listed().map { Available.fromManifest(cfg, it) })
    when {
        args.flag("names_only") -> {
            printNames(rows)
        }

        rows.isEmpty() && !args.flag("json") -> {
            out("nothing installed")
            out(EMPTY_REGISTRY)
        }

        else -> {
            showAll(cfg, args, resolver, rows)
        }
    }
}

private fun showAll(
    cfg: Config,
    args: Matches,
    resolver: Resolver,
    all: List<Row>,
) {
    if (resolver.registryIsEmpty()) note(EMPTY_REGISTRY)
    widen(args, all)
    val offline = fillLatest(ctx(cfg), SourceRegistry.load(ctx(cfg)), all).offline()
    // No source answered: the network is missing, not one package. What is installed is still known, so that much is
    // the answer.
    val rows = if (offline) all.filter { it.local != null } else all
    when {
        args.flag("json") -> {
            printJson(allJson(rows))
        }

        offline -> {
            printLocalTable(rows)
            out("latest: offline")
        }

        else -> {
            printAllTable(rows)
        }
    }
}

private fun allJson(rows: List<Row>) =
    jsonOf(
        "packages" to
            JsonArray(
                rows.map { row ->
                    val local = row.local
                    jsonOf(
                        "name" to JsonPrimitive(printable(row.name)),
                        "installed" to JsonPrimitive(local?.build?.version?.toString()),
                        "channel" to JsonPrimitive(local?.build?.channel?.asStr),
                        "latest" to JsonPrimitive(row.latestVersion()),
                        "update_available" to JsonPrimitive(row.updateAvailable()),
                        "pinned" to JsonPrimitive(local?.pinned == true),
                        "source" to JsonPrimitive(row.source()?.toString()),
                    )
                },
            ),
        "unreachable" to JsonArray(unreachable(rows).map { JsonPrimitive(printable(it)) }),
    )

private fun printAllTable(rows: List<Row>) {
    val marker = installedMarker()
    val cells =
        rows.map { row ->
            listOf(
                if (row.local != null) marker else "",
                row.name,
                row.local?.cell().orEmpty(),
                row.latestCell(),
                row.source()?.toString().orEmpty(),
            )
        }
    tableStyled(listOf("", "package", "installed", "latest", "source"), cells) { r, c, text ->
        val row = rows[r]
        when {
            c == NAME && row.local != null -> bold(text)
            c == LATEST && row.updateAvailable() && text.endsWith(UPDATE) -> text.removeSuffix(UPDATE) + tone(Tone.Warning, UPDATE)
            else -> text
        }
    }
    updateFooter(rows)?.let { out(printable(it)) }
    unreachableNote(rows)?.let { out(printable(it)) }
}

// The columns of `list` that are painted.
private const val NAME = 1
private const val LATEST = 3

/** The first-column mark of an installed package, in `list` and in `releases`. */
internal fun installedMarker() = if (colorEnabled()) "●" else "*"

/** What `outdated` found, in the order the packages were checked. */
private class Checked {
    val rows = mutableListOf<List<String>>()
    val outdated = mutableListOf<JsonObject>()
    val failed = mutableListOf<JsonObject>()
    var checked = 0
}

/**
 * Reports installed packages that have a newer upstream release.
 *
 * `--json` writes an object, not a bare array: `status`, `outdated` (packages with a newer release), `failed` (each
 * source that could not be checked), and `unreachable` (that count). A failed check still exits non-zero, matching the
 * text "N could not be checked".
 */
fun outdated(
    cfg: Config,
    args: Matches,
) {
    val state = State.load(cfg)
    val sources = SourceRegistry.load(ctx(cfg))
    val prerelease = args.flag("prerelease") || cfg.prerelease
    val draft = args.flag("draft")
    // Local packages have no upstream release stream; skipping them keeps `outdated` from treating the synthetic tag as
    // something to refresh.
    val pkgs = state.iter().filter { it.source.scheme != "local" }
    // One after another whatever `--jobs` says, as every batch runs for now; the answers are read only once all are in,
    // so the warnings follow the `checking` lines as they do when the checks run side by side.
    val results =
        pkgs.map { pkg ->
            step("checking", pkg.name)
            try {
                Result.success(latestRelease(sources, pkg, prerelease, draft))
            } catch (e: KetchError) {
                Result.failure(e)
            }
        }
    val found = Checked()
    pkgs.zip(results).forEach { (pkg, result) -> tally(found, pkg, result, prerelease, draft) }
    reportOutdated(args.flag("json"), found, pkgs.size)
}

private fun tally(
    found: Checked,
    pkg: InstalledPackage,
    result: Result<Release>,
    prerelease: Boolean,
    draft: Boolean,
) {
    val release =
        result.getOrElse { e ->
            // Reporting is best-effort: one unreachable source must not hide the rest of the answer.
            warn("${pkg.name}: ${e.message}")
            found.failed += jsonOf("name" to JsonPrimitive(pkg.name), "error" to JsonPrimitive(e.message))
            return
        }
    found.checked += 1
    if (!isUpgrade(report(), InstalledBuild.of(pkg), release, upgradeOpts(pkg, prerelease, draft))) return
    found.rows += listOf(pkg.name, pkg.version.toString(), release.version.toString(), if (pkg.pinned) "pinned" else "")
    found.outdated +=
        jsonOf(
            "name" to JsonPrimitive(pkg.name),
            "installed" to JsonPrimitive(pkg.version.toString()),
            "latest" to JsonPrimitive(release.version.toString()),
            "tag" to JsonPrimitive(release.tag),
            "pinned" to JsonPrimitive(pkg.pinned),
        )
}

private fun reportOutdated(
    json: Boolean,
    found: Checked,
    total: Int,
) {
    val unreachable = found.failed.size
    if (json) printJson(outdatedReport(found.checked, found.outdated, found.failed))
    if (unreachable > 0) {
        throw KetchError.Msg(
            if (found.checked == 0) {
                "could not check any of the $unreachable packages; see the warnings above"
            } else {
                "$unreachable of $total packages could not be checked; see the warnings above"
            },
        )
    }
    when {
        json -> Unit
        found.rows.isEmpty() -> out("everything is up to date")
        else -> table(listOf("package", "installed", "latest", ""), found.rows)
    }
}

internal fun outdatedStatus(
    checked: Int,
    failed: Int,
) = when {
    failed == 0 -> "ok"
    checked == 0 -> "fail"
    else -> "partial"
}

/**
 * JSON object for `outdated --json`.
 *
 * `outdated` is the packages with a newer release. `failed` lists each source that could not be checked. `unreachable`
 * is that count, so a machine reader does not treat an empty package list as "everything current".
 */
internal fun outdatedReport(
    checked: Int,
    outdated: List<JsonObject>,
    failed: List<JsonObject>,
) = jsonOf(
    "status" to JsonPrimitive(outdatedStatus(checked, failed.size)),
    "outdated" to JsonArray(outdated),
    "failed" to JsonArray(failed),
    "unreachable" to JsonPrimitive(failed.size),
)

/** Somebody else's prose on its way into JSON: what `printable` lets through. */
internal fun jsonProse(value: String?): String? = value?.let(::printable)
