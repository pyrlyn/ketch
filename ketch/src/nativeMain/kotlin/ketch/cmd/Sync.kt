// Port of cmd/lock.rs: `ketch lock` writes a reproducible record of what is installed, `ketch sync` makes a machine
// match one. The file's shape and its checks live in ketch.core.lockfile; the installing is the pipeline `install` uses.
package ketch.cmd

import cli.Matches
import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.install.InstallRequest
import ketch.core.install.batch
import ketch.core.lockfile.LockedPackage
import ketch.core.lockfile.Lockfile
import ketch.core.lockfile.Plan
import ketch.core.manifest.Resolver
import ketch.core.model.PackageSpec
import ketch.core.model.VersionSpec
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import ketch.core.text.asciiLowercase
import ketch.ui.Tone
import ketch.ui.confirm
import ketch.ui.ctx
import ketch.ui.debug
import ketch.ui.dim
import ketch.ui.out
import ketch.ui.success
import ketch.ui.tone
import kotlinx.io.files.Path
import ketch.core.install.uninstall as removePackage
import ketch.core.lockfile.path as lockfilePath
import ketch.core.lockfile.plan as planSync

/** Write the lockfile, or compare it against the tree. */
fun lock(
    cfg: Config,
    args: Matches,
) {
    val path = lockfilePath(args.value("file")?.let(::Path))
    val state = State.load(cfg)
    if (args.flag("check")) {
        val plan = planSync(Lockfile.load(path), state)
        reportPlan(plan)
        if (!plan.isClean(false)) {
            throw KetchError.Msg("$path does not describe what is installed; run `ketch sync` or `ketch lock`")
        }
        success("clean", "$path matches")
        return
    }
    val lock = Lockfile.fromState(state)
    lock.save(path)
    success("wrote", "$path (${lock.packages.size} packages)")
}

/** Bring the tree in line with the lockfile. */
fun sync(
    cfg: Config,
    args: Matches,
) {
    val path = lockfilePath(args.value("file")?.let(::Path))
    val lock = Lockfile.load(path)
    Lock.acquire(ctx(cfg)).use {
        val sources = SourceRegistry.load(ctx(cfg))
        val state = State.load(cfg)
        val plan = planSync(lock, state)
        if (plan.isClean(args.flag("prune"))) {
            success("in sync", "${plan.matched} packages match $path")
            return
        }
        reportPlan(plan)
        if (!args.flag("dry_run")) apply(cfg, args, sources, state, plan)
    }
}

private fun apply(
    cfg: Config,
    args: Matches,
    sources: SourceRegistry,
    state: State,
    plan: Plan,
) {
    // Removing something the lockfile merely fails to mention is the one part of a sync that can lose work, so it is
    // asked about rather than assumed.
    val prune =
        args.flag("prune") &&
            plan.extra.isNotEmpty() &&
            (args.flag("yes") || confirm("remove ${plan.extra.size} packages not in the lockfile?", false))
    val wanted = plan.missing + plan.changed.map { it.first }
    val tally = Tally()
    val reqs = wanted.map { requestFor(cfg, it, cfg.target.toString()) }
    for ((entry, outcome) in wanted.zip(batch(ctx(cfg), sources, state, reqs, jobs(cfg, args)))) {
        outcome
            .onSuccess { done ->
                tally.done += 1
                // A pin is part of what the lockfile records, so it is restored rather than leaving the fresh install
                // quietly upgradeable.
                state.get(done.pkg.name)?.let { state.insert(it.copy(pinned = entry.pinned)) }
                success("installed", "${done.pkg.name} ${done.pkg.version}")
            }.onFailure { e ->
                ketch.ui.error(e as KetchError)
                tally.failed += entry.name
            }
    }
    if (prune) pruneExtras(cfg, state, plan.extra, tally)
    // Saved even on a partial failure, so the file matches what is on disk.
    if (tally.done > 0) state.save(cfg)
    if (tally.failed.isNotEmpty()) {
        // Extras are only attempted under `--prune`; counting them otherwise reports failures out of a total the run
        // never touched.
        val attempted = wanted.size + if (prune) plan.extra.size else 0
        throw KetchError.Msg("${tally.failed.size} of $attempted packages failed: ${tally.failed.joinToString(", ")}")
    }
    success("synced", "${tally.done} packages")
}

private fun pruneExtras(
    cfg: Config,
    state: State,
    extra: List<String>,
    tally: Tally,
) {
    for (name in extra) {
        try {
            val pkg = removePackage(ctx(cfg), state, name)
            tally.done += 1
            success("removed", "${pkg.name} ${pkg.version}")
        } catch (e: KetchError) {
            ketch.ui.error(e)
            tally.failed += name
        }
    }
}

/** One install request for a locked entry, at exactly the tag recorded for it. */
private fun requestFor(
    cfg: Config,
    entry: LockedPackage,
    target: String,
): InstallRequest {
    val req =
        InstallRequest(
            spec = specFor(cfg, entry),
            requireChecksum = cfg.requireChecksums,
            // The recorded name is the one the package lives under, which `--name` may have chosen. Resolving the
            // source alone would infer another, and install the package somewhere `lock --check` never looks.
            nameOverride = entry.name,
            // Same tag with a different hash is still "already installed" unless we say otherwise; sync has to
            // replace the drifted payload.
            force = true,
            // Sync runs where nobody may be at a terminal, and a fresh machine has no state to remember a choice in:
            // the lockfile's is the one to repeat.
            lockedBin = entry.bin,
        )
    // The recorded hash describes an asset for the machine that wrote the lock. Somewhere else it names a file this
    // host cannot even run, so holding the download to it would fail every cross-platform sync.
    if (entry.matchesTarget(target)) {
        // A hash is only checkable against the file it was taken from, so the recorded asset is pinned with it.
        // Re-scoring could pick another asset under the same tag and report it as a rewritten release.
        return req.copy(expectedSha256 = entry.sha256, assetOverride = entry.asset)
    }
    debug("${entry.name}: locked on ${entry.target}, re-selecting the asset for $target")
    // Without a hash that applies here, a floating tag republished since the lock was written would install its new
    // build unnoticed.
    return req.copy(expectedPublished = entry.published)
}

/**
 * How to ask for a locked package. The name goes first because that is how the package was found originally, and
 * which manifest tier answers decides what gets linked and under what names. It is used only while it still means the
 * same project: a name that now resolves somewhere else must not quietly install that instead.
 */
private fun specFor(
    cfg: Config,
    entry: LockedPackage,
): PackageSpec {
    val version = VersionSpec.Exact(entry.tag)
    val byName = PackageSpec(raw = entry.name, reference = null, alias = entry.name.asciiLowercase(), version = version)
    val resolved = runCatching { Resolver.load(ctx(cfg)).resolve(byName).first }.getOrNull()
    if (resolved?.source == entry.source) return byName
    return PackageSpec(raw = entry.source.toString(), reference = entry.source, alias = null, version = version)
}

private fun reportPlan(plan: Plan) {
    // Names and tags come out of a lockfile, which a colleague may have written or a repository may have handed over.
    for (entry in plan.missing) out("${tone(Tone.Success, "+")} ${sanitize(entry.name)} ${dim(sanitize(entry.tag))}")
    for ((entry, have) in plan.changed) {
        out("${tone(Tone.Warning, "~")} ${sanitize(entry.name)} ${dim(sanitize("$have -> ${entry.tag}"))}")
    }
    for (name in plan.extra) out("${tone(Tone.Error, "-")} $name ${dim("not in the lockfile")}")
    if (plan.matched > 0) out(dim("${plan.matched} already match"))
}
