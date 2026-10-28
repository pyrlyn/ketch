package ketch.serve

import ketch.core.cancel.Cancel
import ketch.core.changelog.findFile
import ketch.core.changelog.fromFile
import ketch.core.changelog.published
import ketch.core.changelog.publishedRange
import ketch.core.config.Config
import ketch.core.decide.NoDecider
import ketch.core.doctor.checks
import ketch.core.error.KetchError
import ketch.core.info.gather
import ketch.core.install.InstallRequest
import ketch.core.install.batch
import ketch.core.install.leftoverPackageDir
import ketch.core.install.pin
import ketch.core.install.prune
import ketch.core.install.removePackageDir
import ketch.core.install.rollback
import ketch.core.install.uninstall
import ketch.core.install.upgradeRequest
import ketch.core.listing.Available
import ketch.core.listing.Local
import ketch.core.listing.Row
import ketch.core.listing.fillCached
import ketch.core.listing.fillLatest
import ketch.core.listing.merge
import ketch.core.log.Log
import ketch.core.manifest.Resolver
import ketch.core.model.PackageSpec
import ketch.core.model.VersionSpec
import ketch.core.process.offerToStop
import ketch.core.report.Ctx
import ketch.core.report.LogReporter
import ketch.core.report.Report
import ketch.core.shell.installHere
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import ketch.wire.Changelog
import ketch.wire.Check
import ketch.wire.HistoryEvent
import ketch.wire.InstallOptions
import ketch.wire.Installed
import ketch.wire.Package
import ketch.wire.PackageInfo
import ketch.wire.PathChange
import ketch.wire.PathStatus
import ketch.wire.Pruned
import ketch.wire.Repository
import ketch.wire.SearchResults
import ketch.wire.Settings
import ketch.wire.Upgrade
import kotlinx.io.files.Path
import ketch.core.doctor.fix as doctorRepair
import ketch.core.install.Installed as CoreInstalled
import ketch.core.link.packageName as linkedPackage
import ketch.core.registry.update as refreshRegistry
import ketch.core.selfupdate.displayVersion as coreVersion
import ketch.core.shell.status as shellStatus
import ketch.core.stats.history as statsHistory
import ketch.wire.KetchError as WireError

/** The version of the core this binary was built from, as `ketch --version` prints it. */
fun ketchVersion() = coreVersion()

/**
 * The package a `ketch://package/<name>` link opens, or the reason the link is refused. A link only ever opens a
 * package page, never installs, and everything in it is untrusted.
 */
fun packageForLink(url: String): String = failing { linkedPackage(url) }

/**
 * The core for one ketch root, one method per thing a person does: the port of `ketch-ffi`'s `KetchCore`, which
 * `ketch serve` answers the desktop app's calls with. Each method is a thin call into the code the CLI command runs,
 * so the two cannot drift apart.
 *
 * The listener, asker and cancel are passed to each call, not to the constructor, so two calls in flight keep their
 * events and questions apart. Each call builds its configuration and opens the log afresh, so an edit to
 * `config.toml` is seen by the next one. A call that changes the install tree holds the lock for its whole run; a
 * second one fails at once as `busy` rather than waiting. Every failure is thrown as [CallFailed].
 */
@Suppress("TooManyFunctions") // One method per call of the protocol.
class KetchCore(
    private val root: Path?,
) {
    /** What one call runs with: its own configuration, the reporter that logs and forwards, and its questions. */
    private class Operation(
        val cfg: Config,
        val report: Report,
        val asker: Asker?,
        val cancel: Cancel,
    ) {
        /** Without an asker every question is declined, as under `ketch --yes`. */
        fun ctx() = Ctx(cfg, report, asker?.let(::AskerDecider) ?: NoDecider, cancel)
    }

    /** The ketch root this core manages, resolved as the next call will. */
    fun root(): String = failing { operation().cfg.root.toString() }

    /** Installed packages, by name. Reads `state.json` only; no network. */
    fun installed(): List<Package> = failing { State.load(operation().cfg).iter().map { it.toWire() } }

    /**
     * Packages matching [query]: what the registry and the person's own manifests know first, then repositories the
     * sources find, [limit] in all. A source that cannot be reached is a warning; only when every one failed and
     * nothing was found is it an error. An empty [query] browses everything known, and asks no source, since none can
     * rank a repository against nothing.
     */
    fun search(
        query: String,
        limit: Int,
        listener: Listener? = null,
        cancel: Cancel? = null,
    ): SearchResults =
        failing {
            val wanted = query.trim()
            val most = limit.coerceAtLeast(0)
            val op = operation(listener, null, cancel)
            val cx = op.ctx()
            val manifests = Resolver.load(cx).search(wanted).take(most)
            // One row each, not `merge`: that sorts by name, and search results keep the resolver's order.
            val rows = manifests.map { Row.offered(Available.fromManifest(op.cfg, it)) }
            fillCached(cx, rows)
            val known = manifests.zip(rows) { manifest, row -> registryPackage(manifest, row.latestVersion()) }
            val rest = most - known.size
            val (repositories, unreachable) = if (rest > 0 && wanted.isNotEmpty()) repositories(op, wanted, rest) else Found()
            // "Nothing found" after every source failed would be a claim made from no evidence.
            if (known.isEmpty() && repositories.isEmpty() && unreachable > 0) {
                throw CallFailed(WireError.Network("no source could be searched for `$wanted` ($unreachable failed)"))
            }
            SearchResults(known, repositories)
        }

    /** What the sources found for a search, and how many of them could not be asked. */
    private data class Found(
        val repositories: List<Repository> = emptyList(),
        val unreachable: Int = 0,
    )

    private fun repositories(
        op: Operation,
        query: String,
        rest: Int,
    ): Found {
        var unreachable = 0
        val found = mutableListOf<Repository>()
        for (source in SourceRegistry.load(op.ctx()).all) {
            try {
                source.search(query, rest).mapTo(found) { repository(source.scheme(), it) }
            } catch (e: KetchError) {
                op.report.warn("${source.scheme()}: ${e.message}")
                unreachable += 1
            }
        }
        return Found(found.take(rest), unreachable)
    }

    /**
     * Installed packages with a newer release, under the rules `ketch outdated` uses, except that a pinned package is
     * reported too, marked [Upgrade.pinned]. Local packages never have one.
     */
    fun outdated(
        listener: Listener? = null,
        cancel: Cancel? = null,
    ): List<Upgrade> =
        failing {
            val op = operation(listener, null, cancel)
            upgrades(op.ctx(), State.load(op.cfg), null)
        }

    /**
     * Install [specs], several at once. An installed package with a newer release is updated. One failure does not
     * undo the others: what succeeded is recorded, then the failure is thrown — the package's own error for a single
     * spec, a summary for several.
     */
    fun install(
        specs: List<String>,
        options: InstallOptions = InstallOptions(),
        listener: Listener? = null,
        asker: Asker? = null,
        cancel: Cancel? = null,
    ): List<Installed> =
        failing {
            val distinct = specs.distinct()
            if (distinct.isEmpty()) throw CallFailed(WireError.Other("nothing to install"))
            if (options.bin != null && distinct.size > 1) {
                throw CallFailed(WireError.Other("a binary choice names one package, so it needs exactly one spec"))
            }
            val op = operation(listener, asker, cancel)
            val cx = op.ctx()
            Lock.acquire(cx).use {
                val sources = SourceRegistry.load(cx)
                val state = State.load(op.cfg)
                val reqs = distinct.map { installRequest(op.cfg, options, it, op.cancel) }
                val outcomes = batch(cx, sources, state, reqs, op.cfg.jobs)
                settle(op.cfg, state, reqs.map { it.spec.label() }, outcomes)
            }
        }

    /**
     * Upgrade [names], or every installed package when empty, to the releases [outdated] reports, leaving pinned
     * packages where they are. Processes holding the files about to be replaced are put to [asker].
     */
    fun upgrade(
        names: List<String>,
        listener: Listener? = null,
        asker: Asker? = null,
        cancel: Cancel? = null,
    ): List<Installed> =
        failing {
            val op = operation(listener, asker, cancel)
            val cx = op.ctx()
            Lock.acquire(cx).use {
                val sources = SourceRegistry.load(cx)
                val state = State.load(op.cfg)
                val wanted = if (names.isEmpty()) null else installedNames(state, names)
                val plan = upgrades(cx, state, wanted).filterNot { it.pinned }
                val pkgs = plan.mapNotNull { upgrade -> state.get(upgrade.name)?.let { upgrade to it } }
                val files = pkgs.flatMap { (_, pkg) -> pkg.links.flatMap { listOf(it.link, it.target) } }
                val reqs = pkgs.map { (upgrade, pkg) -> upgradeRequest(op.cfg, pkg, upgrade.tag, null, op.cancel) }
                if (reqs.isEmpty()) {
                    emptyList()
                } else {
                    offerToStop(files, false, cx)
                    settle(op.cfg, state, pkgs.map { it.first.name }, batch(cx, sources, state, reqs, op.cfg.jobs))
                }
            }
        }

    /**
     * Remove [names]: their links, their files and their records. Every name is checked first, so an unknown one stops
     * the call with `not_found` before any installed package is removed — though, as `ketch uninstall` does, a
     * `store/<name>/` folder a failed uninstall left behind for that exact name is taken away when [asker] confirms it.
     * [cancel] is checked before each package: what was removed by then stays removed.
     */
    fun uninstall(
        names: List<String>,
        listener: Listener? = null,
        asker: Asker? = null,
        cancel: Cancel? = null,
    ): List<Package> =
        failing {
            val op = operation(listener, asker, cancel)
            val cx = op.ctx()
            Lock.acquire(cx).use {
                val state = State.load(op.cfg)
                refuseMissing(op, cx, state, names)
                val removed = mutableListOf<Package>()
                val failed = mutableListOf<Pair<String, Throwable>>()
                val targets = installedNames(state, names)
                for (name in targets) {
                    try {
                        op.cancel.check()
                        op.report.step("removing", name)
                        val pkg = uninstall(cx, state, name)
                        op.report.success("removed", "${pkg.name} ${pkg.version}")
                        removed += pkg.toWire()
                    } catch (e: KetchError) {
                        failed += name to e
                    }
                }
                if (removed.isNotEmpty()) state.save(op.cfg)
                summarise(targets.size, failed)?.let { throw CallFailed(it) }
                removed
            }
        }

    /** `not_found` for the first unknown name, after offering to remove each one's leftover folder. */
    private fun refuseMissing(
        op: Operation,
        cx: Ctx,
        state: State,
        names: List<String>,
    ) {
        val missing = names.filter { state.find(it) == null }
        for (name in missing) {
            // Removing a folder is the person's call, as the CLI asks it; a host with no asker keeps it.
            val dir = leftoverPackageDir(op.cfg, state, name)
            if (dir != null && cx.decider.confirmRemoveLeftover(dir)) removePackageDir(op.cfg, name, op.report)
        }
        missing.firstOrNull()?.let { throw CallFailed(WireError.NotFound(it)) }
    }

    /**
     * What changed in [pkg]: the installed version when [version] is null, read from the changelog file the release
     * ships when it has a section for that version, else from the notes the release published.
     */
    fun changelog(
        pkg: String,
        version: String?,
        listener: Listener? = null,
    ): Changelog =
        failing {
            val op = operation(listener)
            val spec = PackageSpec.parse(if (version != null) "$pkg@$version" else pkg)
            val installed = State.load(op.cfg).findSpec(spec)
            // Only the installed version has a file on disk. A file with no section for it is not an answer about it,
            // so the published notes come first and the whole file is the fallback.
            val file =
                installed?.takeIf { spec.version == VersionSpec.Latest }?.let { local ->
                    findFile(local.prefix)?.let { changelog(local.name, local.version.toString(), fromFile(it, local.version.toString())) }
                }
            if (file?.heading != null) return@failing file
            try {
                val notes = published(op.ctx(), spec, installed, false)
                changelog(notes.name, notes.version, notes.entry)
            } catch (e: KetchError) {
                if (file == null) throw e
                op.report.warn(e.message)
                file
            }
        }

    /**
     * What changed in [pkg] between two versions: one entry per release newer than [from] and no newer than [to],
     * newest first. [from] defaults to the installed version and [to] to the newest release.
     */
    fun changelogRange(
        pkg: String,
        from: String?,
        to: String?,
        listener: Listener? = null,
    ): List<Changelog> =
        failing {
            val op = operation(listener)
            val spec = PackageSpec.parse(pkg)
            val installed = State.load(op.cfg).findSpec(spec)
            val (name, entries) = publishedRange(op.ctx(), spec, installed, from, to)
            entries.map { (version, entry) -> changelog(name, version, entry) }
        }

    /** Every `ketch doctor` check. Repairs nothing. */
    fun doctor(listener: Listener? = null): List<Check> = failing { checks(operation(listener).ctx()).map { it.toWire() } }

    /**
     * What `doctor` can repair on its own, repaired: the PATH setup, when the bin dir is on `PATH` nowhere yet. This
     * edits shell startup files outside the ketch root.
     */
    fun doctorFix(listener: Listener? = null): List<PathChange> = failing { doctorRepair(operation(listener).ctx()).map { it.toWire() } }

    /** What `stats.db` recorded, newest first: every package's history, or [pkg]'s alone, at most [limit] events. */
    fun history(
        pkg: String?,
        limit: Int,
    ): List<HistoryEvent> = failing { statsHistory(operation().cfg, pkg, limit.coerceAtLeast(0).toLong()).map { it.toWire() } }

    /** What `ketch info` shows about [pkg], installed or not. A source that cannot be reached leaves its part out. */
    fun info(
        pkg: String,
        listener: Listener? = null,
    ): PackageInfo =
        failing {
            val op = operation(listener)
            gather(op.ctx(), State.load(op.cfg), pkg).toWire()
        }

    /** Hold [names] at their installed versions, or every installed package when empty. */
    fun pin(names: List<String>): List<Package> = setPinned(names, true)

    /** Let [names] be upgraded again, or every installed package when empty. */
    fun unpin(names: List<String>): List<Package> = setPinned(names, false)

    private fun setPinned(
        names: List<String>,
        pinned: Boolean,
    ): List<Package> =
        failing {
            val op = operation()
            Lock.acquire(op.ctx()).use {
                val state = State.load(op.cfg)
                val changed = selected(state, names).map { pin(state, it, pinned).toWire() }
                state.save(op.cfg)
                changed
            }
        }

    /** Switch [pkg] back to a version still on disk: [to], or the newest one older than what is installed. */
    fun rollback(
        pkg: String,
        to: String?,
        listener: Listener? = null,
        asker: Asker? = null,
    ): Installed =
        failing {
            val op = operation(listener, asker)
            val cx = op.ctx()
            Lock.acquire(cx).use {
                val state = State.load(op.cfg)
                val out = rollback(cx, state, pkg, to)
                state.save(op.cfg)
                out.toWire()
            }
        }

    /**
     * Remove retained versions of [names], or of every installed package when empty, beyond the newest [keep]. A [keep]
     * given here becomes the retention setting, as `ketch prune --keep` makes it.
     */
    fun prune(
        names: List<String>,
        keep: Int?,
        listener: Listener? = null,
    ): List<Pruned> =
        failing {
            if (keep != null && keep < 0) throw CallFailed(WireError.Other("keep must not be negative: $keep"))
            val op = operation(listener)
            val cx = op.ctx()
            Lock.acquire(cx).use {
                val state = State.load(op.cfg)
                if (keep != null) state.retention = state.retention.copy(keep = keep.toUInt())
                val pruned =
                    selected(state, names).mapNotNull { name ->
                        prune(cx, state, name, state.retention.keep)
                            .takeIf { it.isNotEmpty() }
                            ?.let { versions -> Pruned(name, versions.map { it.toString() }) }
                    }
                state.save(op.cfg)
                pruned
            }
        }

    /** Fetch the package registry afresh and swap it in, as `ketch update` does, returning how many packages it has. */
    fun registryRefresh(
        listener: Listener? = null,
        cancel: Cancel? = null,
    ): Long = failing { refreshRegistry(operation(listener, null, cancel).ctx()).toLong() }

    /** Whether the bin dir is on `PATH`, and for each shell whether its startup file sets it up. Writes nothing. */
    fun pathStatus(): PathStatus =
        failing {
            val cfg = operation().cfg
            pathStatus(cfg.binDir, shellStatus(cfg))
        }

    /**
     * Put the bin dir on `PATH` in every shell in use, as `ketch path install` does. With [dryRun] nothing is written.
     * When no shell in use can be told apart it is an error, rather than an edit to a file nothing reads.
     */
    fun pathInstall(
        dryRun: Boolean,
        listener: Listener? = null,
    ): List<PathChange> = failing { installHere(operation(listener).cfg, dryRun).map { it.toWire() } }

    /** ketch's effective configuration. Only whether a GitHub token is set crosses, never the token. */
    fun config(): Settings = failing { operation().cfg.toWire() }

    /** Configuration and log for one call, built afresh: nothing carries over between calls. */
    private fun operation(
        listener: Listener? = null,
        asker: Asker? = null,
        cancel: Cancel? = null,
    ): Operation {
        val forward = listener?.let { Report(ListenerReporter(it)) }
        val report = Report(LogReporter(forward))
        val cfg = Config.load(root, report)
        cfg.ensureDirs()
        try {
            Log.init(cfg, false)
        } catch (e: KetchError) {
            // Past the log: reporting it through `report` would log the failure to the log that failed.
            forward?.warn(e.message)
        }
        return Operation(cfg, report, asker, cancel ?: Cancel())
    }
}

/** The core's failures as the app is told them. */
@Suppress("TooGenericExceptionCaught") // A library under the core may throw its own exception; the call must still end.
internal inline fun <T> failing(body: () -> T): T =
    try {
        body()
    } catch (e: Exception) {
        throw e as? CallFailed ?: CallFailed(e.toWire(), e)
    }

/** What `install` asks the core for one [raw] spec under [options]. */
internal fun installRequest(
    cfg: Config,
    options: InstallOptions,
    raw: String,
    cancel: Cancel,
) = InstallRequest(
    spec = PackageSpec.parse(raw),
    force = options.force,
    prerelease = options.prerelease,
    link = options.link,
    requireChecksum = options.requireChecksum || cfg.requireChecksums,
    allowUnverified = options.allowUnverified,
    bin = options.bin,
    cancel = cancel,
)

/** The installed names [names] refer to, or every installed name when it is empty, as `pin` and `prune` read it. */
private fun selected(
    state: State,
    names: List<String>,
): List<String> = if (names.isEmpty()) state.iter().map { it.name } else installedNames(state, names)

/** The installed names [names] refer to, sorted, or `not_found` for the first that matches nothing. */
private fun installedNames(
    state: State,
    names: List<String>,
): List<String> = names.map { state.find(it)?.name ?: throw CallFailed(WireError.NotFound(it)) }.distinct().sorted()

/** The upgrades on offer for installed packages, all of them or those in [only]: `ketch list`'s lookups and rules. */
private fun upgrades(
    cx: Ctx,
    state: State,
    only: List<String>?,
): List<Upgrade> {
    val locals = state.iter().filter { only == null || it.name in only }.map { Local.fromInstalled(cx.cfg, it) }
    val rows = merge(locals, emptyList())
    if (fillLatest(cx, SourceRegistry.load(cx), rows).offline()) {
        throw CallFailed(WireError.Network("could not reach any package source to check the latest versions"))
    }
    return rows.mapNotNull(::upgradeOf)
}

/** Record what an install batch placed, then answer for the batch. */
private fun settle(
    cfg: Config,
    state: State,
    labels: List<String>,
    outcomes: List<Result<CoreInstalled>>,
): List<Installed> {
    val done = outcomes.mapNotNull { it.getOrNull()?.toWire() }
    val failed = labels.zip(outcomes).mapNotNull { (label, outcome) -> outcome.exceptionOrNull()?.let { label to it } }
    // Saved before any failure is thrown: one bad package must not discard the ones already placed.
    if (done.isNotEmpty()) state.save(cfg)
    summarise(labels.size, failed)?.let { throw CallFailed(it) }
    return done
}

/**
 * The error for a batch of [total] in which [failed] went wrong, if any did: a lone package's own error, `cancelled`
 * when cancelling is all that happened, otherwise one message naming each failure.
 */
internal fun summarise(
    total: Int,
    failed: List<Pair<String, Throwable>>,
): WireError? =
    when {
        failed.isEmpty() -> {
            null
        }

        total == 1 && failed.size == 1 -> {
            failed.single().second.toWire()
        }

        failed.all { it.second is KetchError.Cancelled } -> {
            WireError.Cancelled
        }

        else -> {
            val lines = listOf("${failed.size} of $total packages failed") + failed.map { (label, e) -> "$label: ${e.toWire().display()}" }
            WireError.Other(lines.joinToString("\n"))
        }
    }
