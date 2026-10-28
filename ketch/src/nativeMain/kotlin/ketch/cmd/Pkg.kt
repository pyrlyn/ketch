// Commands that change what is installed: the port of src/cmd/pkg.rs.
//
// Each one takes the lock for the whole batch and writes `state.json` once at the end, so an interrupted run leaves the
// file either fully old or fully new.
package ketch.cmd

import cli.Matches
import ketch.core.cancel.Cancel
import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.install.InstallRequest
import ketch.core.install.Installed
import ketch.core.install.InstalledBuild
import ketch.core.install.batch
import ketch.core.install.isUpgrade
import ketch.core.install.latestRelease
import ketch.core.install.leftoverPackageDir
import ketch.core.install.relink
import ketch.core.install.removePackageDir
import ketch.core.install.unlink
import ketch.core.install.upgradeOpts
import ketch.core.install.upgradeRequest
import ketch.core.model.InstalledPackage
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.VersionSpec
import ketch.core.process.offerToStop
import ketch.core.shell.configuredIn
import ketch.core.source.SourceRegistry
import ketch.core.source.absolutePath
import ketch.core.state.Lock
import ketch.core.state.State
import ketch.ui.activity
import ketch.ui.bareError
import ketch.ui.canAsk
import ketch.ui.confirm
import ketch.ui.ctx
import ketch.ui.ctxAsking
import ketch.ui.debug
import ketch.ui.out
import ketch.ui.report
import ketch.ui.step
import ketch.ui.success
import ketch.ui.table
import ketch.ui.warn
import ketch.core.install.pin as pinPackage
import ketch.core.install.prune as prunePackage
import ketch.core.install.rollback as rollBack
import ketch.core.install.uninstall as removePackage

/** `NotInstalled`'s exit code, which `uninstall` keeps when it has already named each missing package itself. */
private const val NOT_INSTALLED = 4

/** How much of an unverified download's SHA-256 the trust-on-first-use warning shows. */
private const val SHORT_SHA = 12

/** How the packages of one batch fared. */
private class Tally {
    var done = 0
    val failed = mutableListOf<String>()
}

fun install(
    cfg: Config,
    args: Matches,
) {
    maybeAutoUpdate(cfg)
    val packages = args.values("packages")
    val path = args.value("path")
    val yes = args.flag("yes")
    args.value("asset")?.let { asset ->
        if (packages.size > 1 || path != null) {
            throw KetchError.Msg("--asset names one file, so it can only be used with a single package")
        }
        // Naming an asset bypasses the check that stops ketch installing a build for another platform, so it is
        // confirmed rather than assumed.
        if (!yes && !confirm("install `$asset` without checking it runs on ${cfg.target}?", false)) return
    }
    checkInstallArgs(args, packages, path)
    Lock.acquire(ctx(cfg)).use {
        val sources = SourceRegistry.load(ctx(cfg))
        val state = State.load(cfg)
        // --path synthesises a local: ref; otherwise the user-typed PKG list is used. Deduplicate either way so the
        // same package is not prepared twice.
        val wanted = (path?.let { listOf("local:${absolutePath(it)}") } ?: packages).distinct()
        // --name applies to the single package being installed.
        val reqs = wanted.mapIndexed { i, raw -> installRequest(cfg, args, raw, args.value("name").takeIf { i == 0 }) }
        val tally = Tally()
        val updates = installBatch(cfg, args, sources, state, reqs, tally, first = true)
        // The same path `ketch upgrade` takes: prepare and commit the exact release the question named, update hooks
        // included.
        if (updates.isNotEmpty()) installBatch(cfg, args, sources, state, updates, tally, first = false)
        if (tally.done > 0) {
            state.save(cfg)
            pathHint(cfg, state)
        }
        if (tally.failed.isNotEmpty()) {
            throw KetchError.Msg("${tally.failed.size} of ${wanted.size} packages failed: ${tally.failed.joinToString(", ")}")
        }
    }
}

private fun checkInstallArgs(
    args: Matches,
    packages: List<String>,
    path: String?,
) {
    val one = path != null || packages.size == 1
    val wrong =
        when {
            args.value("name") != null && !one -> "--name needs exactly one package (or use it with --path)"
            args.value("bin") != null && !one -> "--bin needs exactly one package (or use it with --path)"
            path != null && packages.isNotEmpty() -> "--path already names the package; do not also pass a PKG argument"
            else -> null
        }
    if (wrong != null) throw KetchError.Msg(wrong)
}

private fun installRequest(
    cfg: Config,
    args: Matches,
    raw: String,
    nameOverride: String?,
) = InstallRequest(
    spec = PackageSpec.parse(raw),
    force = args.flag("force"),
    prerelease = args.flag("prerelease"),
    draft = args.flag("draft"),
    link = !args.flag("no_link"),
    requireChecksum = args.flag("require_checksum") || cfg.requireChecksums,
    assetOverride = args.value("asset"),
    nameOverride = nameOverride,
    bin = args.value("bin"),
    // `--yes` has already answered the question this would ask.
    offerUpdate = !args.flag("yes"),
    allowUnverified = args.flag("allow_unverified") || args.flag("yes"),
    cancel = Cancel(),
)

/**
 * Install [reqs] as one batch and say how each went. Returns the updates a person said yes to: the [first] batch's
 * requests stop on a newer release rather than replace what is installed, and the second, made of those, does not.
 */
private fun installBatch(
    cfg: Config,
    args: Matches,
    sources: SourceRegistry,
    state: State,
    reqs: List<InstallRequest>,
    tally: Tally,
    first: Boolean,
): List<InstallRequest> {
    // A lone package's failure is the command's; the updates were asked for one by one and are tallied like a batch.
    val single = first && reqs.size == 1
    val updates = mutableListOf<InstallRequest>()
    val outcomes = batch(ctxAsking(cfg, !args.flag("yes")), sources, state, reqs, jobs(cfg, args))
    for ((req, outcome) in reqs.zip(outcomes)) {
        val err = outcome.exceptionOrNull() as KetchError?
        when {
            err == null -> {
                tally.done += 1
                reportInstalled(outcome.getOrThrow())
            }

            // Asked here, one package at a time, once every download is done: a question has no place in the middle
            // of a parallel batch.
            err is KetchError.UpdateAvailable && canAsk() -> {
                if (confirm("${err.name} ${err.installed} is installed; update to ${err.latest}?", false)) {
                    updates += req.copy(spec = req.spec.copy(version = VersionSpec.Exact(err.tag)), offerUpdate = false)
                }
            }

            single -> {
                throw err
            }

            // One bad package must not discard the ones that already succeeded, so the failure is held until the state
            // file is saved.
            else -> {
                ketch.ui.error(err)
                tally.failed += req.spec.raw
            }
        }
    }
    return updates
}

fun uninstall(
    cfg: Config,
    args: Matches,
) {
    val yes = args.flag("yes")
    Lock.acquire(ctx(cfg)).use {
        val state = State.load(cfg)
        // Resolve every name up front: a typo should stop the command before it has already removed the packages that
        // did match.
        val targets = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (name in args.values("names")) {
            val pkg = state.find(name)
            when {
                pkg == null -> missing += name
                pkg.name !in targets -> targets += pkg.name
            }
        }
        if (missing.isNotEmpty()) {
            removeLeftovers(cfg, state, missing, yes)
            for (name in missing) bareError("$name: not found")
            throw KetchError.Reported(NOT_INSTALLED)
        }
        if (!yes && !confirm("remove ${targets.joinToString(", ")}?", false)) return
        var removed = 0
        for (name in targets) {
            try {
                val pkg = removePackage(ctx(cfg), state, name)
                removed += 1
                success("removed", "${pkg.name} ${pkg.version}")
            } catch (e: KetchError) {
                ketch.ui.error(e)
            }
        }
        if (removed > 0) state.save(cfg)
        if (removed < targets.size) throw KetchError.Msg("removed $removed of ${targets.size} packages")
    }
}

/**
 * A folder a failed uninstall left behind has no record to match, so this is the only command that will ever take it
 * away. It is still a whole folder deleted on a name nothing resolved, so it is asked for.
 */
private fun removeLeftovers(
    cfg: Config,
    state: State,
    missing: List<String>,
    yes: Boolean,
) {
    val leftovers = missing.mapNotNull { name -> leftoverPackageDir(cfg, state, name)?.let { name to it } }
    if (leftovers.isEmpty()) return
    if (yes || confirm("remove leftover ${leftovers.joinToString(", ") { it.second.toString() }}?", false)) {
        for ((name, _) in leftovers) removePackageDir(cfg, name, report())
    }
}

/** What `upgrade` found: the packages with a newer release, and how many could be asked at all. */
private class UpgradePlan {
    val items = mutableListOf<Pair<InstalledPackage, Release>>()
    var checked = 0
    var unreachable = 0
}

fun upgrade(
    cfg: Config,
    args: Matches,
) {
    maybeAutoUpdate(cfg)
    val names = args.values("names")
    if (args.value("bin") != null && names.size != 1) throw KetchError.Msg("--bin needs exactly one package name")
    Lock.acquire(ctx(cfg)).use {
        val sources = SourceRegistry.load(ctx(cfg))
        val state = State.load(cfg)
        val selected = select(state, names)
        if (selected.isEmpty()) {
            out("nothing installed")
            return
        }
        val plan = planUpgrades(cfg, args, sources, state, selected)
        if (plan.items.isEmpty()) upToDate(plan) else applyUpgrades(cfg, args, sources, state, plan.items)
    }
}

private fun planUpgrades(
    cfg: Config,
    args: Matches,
    sources: SourceRegistry,
    state: State,
    names: List<String>,
): UpgradePlan {
    val plan = UpgradePlan()
    val prerelease = args.flag("prerelease") || cfg.prerelease
    // One bar for the whole check: the count of names is known up front. `outdated` stays on its own step lines. The
    // bar ends with this block so a later install batch can take the terminal.
    activity("checking", names.size.toLong()).use { progress ->
        for (name in names) {
            progress.setMessage(name)
            state.get(name)?.let { consider(plan, sources, it, prerelease, args.flag("draft"), args.flag("force")) }
            progress.inc(1)
        }
    }
    return plan
}

private fun consider(
    plan: UpgradePlan,
    sources: SourceRegistry,
    pkg: InstalledPackage,
    prerelease: Boolean,
    draft: Boolean,
    force: Boolean,
) {
    val skip =
        when {
            pkg.pinned && !force -> "is pinned at ${pkg.version}"
            pkg.source.scheme == "local" -> "is local; upgrade is not applicable"
            else -> null
        }
    if (skip != null) return debug("${pkg.name} $skip")
    step("checking", pkg.name)
    val release =
        try {
            latestRelease(sources, pkg, prerelease, draft)
        } catch (e: KetchError) {
            // An unreachable source for one package must not abandon the rest.
            warn("${pkg.name}: ${e.message}")
            plan.unreachable += 1
            return
        }
    plan.checked += 1
    if (isUpgrade(report(), InstalledBuild.of(pkg), release, upgradeOpts(pkg, prerelease, draft))) plan.items += pkg to release
}

private fun upToDate(plan: UpgradePlan) {
    val (checked, unreachable) = plan.checked to plan.unreachable
    // "Up to date" is a claim about versions we actually saw. With nothing checked we do not know, and exiting 0 tells
    // a script the opposite.
    if (checked == 0 && unreachable > 0) {
        throw KetchError.Msg("could not check any of the $unreachable packages; see the warnings above")
    }
    success("up to date", if (unreachable > 0) "$checked packages ($unreachable could not be checked)" else "$checked packages")
}

private fun applyUpgrades(
    cfg: Config,
    args: Matches,
    sources: SourceRegistry,
    state: State,
    plan: List<Pair<InstalledPackage, Release>>,
) {
    table(listOf("package", "from", "to"), plan.map { (pkg, release) -> listOf(pkg.name, pkg.version.toString(), release.version.toString()) })
    val yes = args.flag("yes")
    if (args.flag("dry_run")) return
    // Defaulting to yes here would answer the question on the user's behalf whenever stdin is not a terminal: `ketch
    // upgrade </dev/null` in a script would upgrade everything unpinned without ever being asked. `--yes` is how a
    // script says yes.
    if (!yes && !confirm("upgrade ${plan.size} packages?", false)) return
    val files = plan.flatMap { (pkg, _) -> pkg.links.flatMap { listOf(it.link, it.target) } }
    offerToStop(files, yes, ctxAsking(cfg, !yes))
    val reqs =
        plan.map { (pkg, release) ->
            upgradeRequest(cfg, pkg, release.tag, args.value("bin"), Cancel()).copy(allowUnverified = args.flag("allow_unverified") || yes)
        }
    val tally = Tally()
    for ((item, outcome) in plan.zip(batch(ctxAsking(cfg, !yes), sources, state, reqs, jobs(cfg, args)))) {
        outcome
            .onSuccess {
                tally.done += 1
                reportInstalled(it)
            }.onFailure {
                ketch.ui.error(it as KetchError)
                tally.failed += item.first.name
            }
    }
    if (tally.done > 0) state.save(cfg)
    if (tally.failed.isNotEmpty()) throw KetchError.Msg("failed to upgrade ${tally.failed.joinToString(", ")}")
}

fun rollback(
    cfg: Config,
    args: Matches,
) {
    Lock.acquire(ctx(cfg)).use {
        val state = State.load(cfg)
        val done = rollBack(ctxAsking(cfg, true), state, args.pkg(), args.value("to"))
        state.save(cfg)
        val pkg = done.pkg
        success("rolled back", done.replaced?.let { "${pkg.name} ${pkg.version} (was $it)" } ?: "${pkg.name} ${pkg.version}")
        pathHint(cfg, state)
    }
}

fun prune(
    cfg: Config,
    args: Matches,
) {
    Lock.acquire(ctx(cfg)).use {
        val state = State.load(cfg)
        args.value("keep")?.let { state.retention = state.retention.copy(keep = it.toUInt()) }
        val keep = state.retention.keep
        val names = select(state, args.values("names"))
        if (names.isEmpty()) {
            out("nothing installed")
            return
        }
        var dropped = 0
        for (name in names) {
            val versions = prunePackage(ctx(cfg), state, name, keep)
            if (versions.isEmpty()) continue
            dropped += versions.size
            success("pruned", "$name (${versions.joinToString(", ")})")
        }
        state.save(cfg)
        if (dropped == 0) out("nothing to prune (keeping $keep previous version${if (keep == 1u) "" else "s"})")
    }
}

/** `pin` and `unpin`: [pinned] selects which. */
fun pin(
    cfg: Config,
    args: Matches,
    pinned: Boolean,
) {
    Lock.acquire(ctx(cfg)).use {
        val state = State.load(cfg)
        for (name in select(state, args.values("names"))) {
            val entry = pinPackage(state, name, pinned)
            success(if (pinned) "pinned" else "unpinned", "${entry.name} ${entry.version}")
        }
        state.save(cfg)
    }
}

/** `link` and `unlink`: [linked] selects which. */
fun link(
    cfg: Config,
    args: Matches,
    linked: Boolean,
) {
    Lock.acquire(ctx(cfg)).use {
        val state = State.load(cfg)
        for (name in select(state, args.values("names"))) {
            if (linked) {
                relink(ctxAsking(cfg, true), state, name)
                success("linked", name)
            } else {
                unlink(ctx(cfg), state, name)
                success("unlinked", name)
            }
        }
        state.save(cfg)
        if (linked) pathHint(cfg, state)
    }
}

/** Turn user-supplied names into installed package names. An empty list means every installed package, which is what the bare `upgrade` form wants. */
private fun select(
    state: State,
    names: List<String>,
): List<String> = if (names.isEmpty()) state.iter().map { it.name } else names.map { state.find(it)?.name ?: throw KetchError.NotInstalled(it) }

/** How many packages to work on at once: `--jobs`, else the configured default, never zero. */
internal fun jobs(
    cfg: Config,
    args: Matches,
): Int = (args.value("jobs")?.let { args.count("jobs") }?.takeIf { it > 0 } ?: cfg.jobs).coerceAtLeast(1)

/** The lines an install leaves behind: what was installed, what it replaced, and what is worth knowing about it. */
internal fun reportInstalled(done: Installed) {
    val pkg = done.pkg
    success("installed", done.replaced?.takeIf { it != pkg.version }?.let { "${pkg.name} ${pkg.version} (was $it)" } ?: "${pkg.name} ${pkg.version}")
    for (link in pkg.binaries()) debug("linked ${link.link}")
    pkg.previousRetained()?.let { debug("retained ${it.version}") }
    // A verified signature establishes the download on its own; the line saying so was printed when it verified.
    if (!pkg.checksumVerified && pkg.provenance == null) {
        warn("${pkg.name} published no checksum; trusting ${pkg.sha256.take(SHORT_SHA)} on first use")
    }
    // The package author's own words, printed for the user: filtered like any other client-app text that reaches a
    // terminal.
    pkg.manifest?.notes?.let { out(sanitize(it)) }
}

/** Say so once, at the end, when the links just made are not reachable. */
internal fun pathHint(
    cfg: Config,
    state: State,
) {
    if (cfg.binDirOnPath() || state.iter().none { it.binaries().isNotEmpty() }) return
    warn("${cfg.binDir} is not on your PATH")
    if (configuredIn(cfg).isEmpty()) {
        out("Run `ketch path install` to add it.")
    } else {
        // Already in the startup file: this shell just predates the edit, and telling them to install again would not
        // change that.
        out("It is in your shell config already — open a new shell.")
    }
}
