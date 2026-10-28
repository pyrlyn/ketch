// The statistics database: what ketch did, and when.
//
// `state.json` records what is installed now, and rewrites itself whole on every change: the right shape for current
// state and the wrong one for history. This is the other half, an append-only record of every install, upgrade,
// rollback and uninstall, in SQLite so it can be queried instead of parsed.
//
// Nothing here is authoritative. Losing `stats.db` costs history, never packages, which is why writing is best effort:
// a package manager that refused to install because it could not record a statistic would trade the user's goal for
// bookkeeping. Reading is not best effort: a query the user asked for reports why it could not answer.
package ketch.core.stats

import app.cash.sqldelight.db.SqlDriver
import db.sqldelight.KitSqlDriver
import fs.createDirAll
import ketch.core.VERSION
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.model.InstalledPackage
import ketch.core.model.nowUnix
import ketch.core.report.Ctx
import ketch.core.stats.db.Events
import ketch.core.stats.db.StatsDatabase
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The one migration Rust ketch has applied, as diesel records it: the folder name's date with the dashes taken out. */
private const val MIGRATION = "20260904000000"

/** What happened to a package. */
enum class Action(
    val text: String,
) {
    Install("install"),
    Upgrade("upgrade"),
    Uninstall("uninstall"),
    Rollback("rollback"),
    ;

    override fun toString() = text
}

/** One row, on its way in. */
data class NewEvent(
    val packageName: String,
    val action: String,
    val version: String,
    val previousVersion: String?,
    val tag: String,
    val source: String,
    val target: String,
    val assetName: String,
    val sha256: String,
    val checksumVerified: Boolean,
    val durationMs: Int?,
    val at: Long,
    val ketchVersion: String,
)

/** One row, on its way out. */
data class Event(
    val id: Long,
    val packageName: String,
    val action: String,
    val version: String,
    val previousVersion: String?,
    val tag: String,
    val source: String,
    val target: String,
    val assetName: String,
    val sha256: String,
    val checksumVerified: Boolean,
    val durationMs: Int?,
    val at: Long,
    val ketchVersion: String,
)

/** Everything `ketch stats` reports, in one pass over the table. */
data class Summary(
    val events: Long = 0,
    val installs: Long = 0,
    val upgrades: Long = 0,
    val uninstalls: Long = 0,
    /** Distinct packages that have ever appeared, installed or not any more. */
    val packages: Long = 0,
    val totalDurationMs: Long = 0,
    /** Rows that carried a duration, which is the divisor for the mean. */
    val timed: Long = 0,
    val firstAt: Long? = null,
    val lastAt: Long? = null,
) {
    /** The mean duration of the timed events, in whole milliseconds; null when none was timed. */
    fun meanDurationMs(): Long? = if (timed > 0) totalDurationMs / timed else null
}

/** An open `stats.db`: the generated queries and the connection they run on, closed together. */
private class Connection(
    val driver: SqlDriver,
) : AutoCloseable {
    val database = StatsDatabase(driver)
    val queries get() = database.eventsQueries

    override fun close() = driver.close()
}

/**
 * Open the database at [path], creating its parent directories and applying the migration Rust ketch would.
 *
 * Another ketch may be installing at the same moment. SQLite serialises writers, and the alternative to waiting is an
 * immediate "database is locked": a recorded install lost to a race that resolves itself in milliseconds.
 */
private fun open(path: Path): Connection {
    val parent = path.parent ?: Path(".")
    fsKitAt(parent) { createDirAll(parent) }
    val connection = step("could not open $path") { Connection(KitSqlDriver(sqlite.open(path.toString()))) }
    try {
        step("could not configure $path") { connection.queries.setBusyTimeout() }
        step("could not migrate $path") { migrate(connection) }
    } catch (e: KetchError) {
        connection.close()
        throw e
    }
    return connection
}

/** diesel's `run_pending_migrations`: the schema and its version row land together or not at all. */
private fun migrate(connection: Connection) =
    connection.database.transaction {
        StatsDatabase.Schema.create(connection.driver)
        if (connection.queries.isApplied(MIGRATION).executeAsOne() == 0L) connection.queries.markApplied(MIGRATION)
    }

private inline fun <T> step(
    what: String,
    body: () -> T,
): T =
    try {
        body()
    } catch (e: db.DbException) {
        throw KetchError.Msg("$what: ${e.message}", e)
    }

/** Record [event], turning any failure into a warning on `cx.report`: the operation it describes has already succeeded. */
fun record(
    cx: Ctx,
    event: NewEvent,
) {
    try {
        recordAt(cx.cfg.statsDb, event)
    } catch (e: KetchError) {
        cx.report.warn("could not record statistics: ${e.message}")
    }
}

/** Record [event] in the database at [path], creating it if needed. */
fun recordAt(
    path: Path,
    event: NewEvent,
) {
    open(path).use { connection ->
        // Every value is bound as a parameter. Package and asset names come from manifests and releases written by
        // other people, and this is why none of them can end a statement early and start their own.
        step("could not record an event") {
            with(event) {
                connection.queries.insert(
                    packageName,
                    action,
                    version,
                    previousVersion,
                    tag,
                    source,
                    target,
                    assetName,
                    sha256,
                    checksumVerified,
                    durationMs?.toLong(),
                    at,
                    ketchVersion,
                )
            }
        }
    }
}

/** The event for installing [pkg]; an upgrade when it [replaced] another version, whichever way the versions compare. */
fun installEvent(
    pkg: InstalledPackage,
    replaced: String?,
    durationMs: Int?,
    version: String,
    source: String,
    target: String,
) = pkg.event(if (replaced != null) Action.Upgrade else Action.Install, version, replaced, source, target, durationMs)

/** The event for removing [pkg]. It carries no duration, so a removal never moves the mean. */
fun uninstallEvent(
    pkg: InstalledPackage,
    version: String,
    source: String,
    target: String,
) = pkg.event(Action.Uninstall, version, null, source, target, null)

/** The event for rolling [pkg] back from [previous] to [version]. */
fun rollbackEvent(
    pkg: InstalledPackage,
    previous: String,
    version: String,
    source: String,
    target: String,
) = pkg.event(Action.Rollback, version, previous, source, target, null)

private fun InstalledPackage.event(
    action: Action,
    version: String,
    previous: String?,
    source: String,
    target: String,
    durationMs: Int?,
) = NewEvent(name, action.text, version, previous, tag, source, target, assetName, sha256, checksumVerified, durationMs, nowUnix(), VERSION)

/** The newest [limit] events, of [packageName] only when given. */
fun history(
    cfg: Config,
    packageName: String?,
    limit: Long,
) = historyAt(cfg.statsDb, packageName, limit)

/**
 * The newest [limit] events in the database at [path], ties in time broken by insertion order.
 *
 * Reading never creates the database: `ketch history` on a machine that has installed nothing answers "nothing" and
 * leaves no file behind, the way a missing `state.json` is an empty state.
 */
fun historyAt(
    path: Path,
    packageName: String?,
    limit: Long,
): List<Event> {
    if (!SystemFileSystem.exists(path)) return emptyList()
    return open(path).use { connection ->
        step("could not read history") {
            val queries = connection.queries
            (if (packageName == null) queries.history(limit) else queries.historyOf(packageName, limit)).executeAsList().map { it.toEvent() }
        }
    }
}

/** What `ketch stats` reports over everything recorded. */
fun summary(cfg: Config) = summaryAt(cfg.statsDb)

/** [summary] of the database at [path]; zeros, without creating it, when there is none. */
fun summaryAt(path: Path): Summary {
    if (!SystemFileSystem.exists(path)) return Summary()
    return open(path).use { connection ->
        step("could not read statistics") {
            with(connection.queries.summary().executeAsOne()) {
                Summary(events, installs, upgrades, uninstalls, packages, total_duration_ms ?: 0, timed, first_at, last_at)
            }
        }
    }
}

private fun Events.toEvent() =
    Event(
        id,
        package_,
        action,
        version,
        previous_version,
        tag,
        source,
        target,
        asset_name,
        sha256,
        checksum_verified,
        duration_ms?.toInt(),
        at,
        ketch_version,
    )
