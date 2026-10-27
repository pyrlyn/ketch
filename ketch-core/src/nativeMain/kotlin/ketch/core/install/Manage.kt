package ketch.core.install

import fs.entryKind
import fs.isDir
import ketch.core.binchoice.Known
import ketch.core.error.KetchError
import ketch.core.hooks.Context
import ketch.core.hooks.Event
import ketch.core.hooks.allowed
import ketch.core.hooks.refusal
import ketch.core.hooks.run
import ketch.core.hooks.runOrWarn
import ketch.core.model.Hooks
import ketch.core.model.InstalledPackage
import ketch.core.model.LinkRecord
import ketch.core.model.PackageKind
import ketch.core.model.RetainedVersion
import ketch.core.model.Version
import ketch.core.model.nowUnix
import ketch.core.platform.Placement
import ketch.core.platform.Platform
import ketch.core.platform.host
import ketch.core.platform.pathIsWithin
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.state.State
import ketch.core.stats.record
import ketch.core.stats.rollbackEvent
import ketch.core.stats.uninstallEvent
import ketch.core.text.eqIgnoreAsciiCase
import ketch.core.text.rustTrim
import kotlinx.io.files.Path

/** The hooks a recorded package may run: none when its manifest has none, and an error when its origin may not run any. */
internal fun recordedHooks(pkg: InstalledPackage): Hooks? {
    val hooks = pkg.manifest?.hooks?.takeIf { !it.isEmpty() } ?: return null
    if (!allowed(pkg.origin)) throw refusal(pkg.origin, pkg.name)
    return hooks
}

private fun State.installed(name: String) = find(name) ?: throw KetchError.NotInstalled(name)

/** Remove a package's links and stored files, then its state entry. Returns the package as it was recorded. */
fun uninstall(
    cx: Ctx,
    state: State,
    name: String,
): InstalledPackage {
    val (cfg, report) = cx.cfg to cx.report
    val pkg = state.installed(name)
    val platform = host()
    val hookCtx = Context(pkg.name, pkg.version.toString(), null, pkg.prefix, cfg.binDir, cfg.root, report)
    // Skipped rather than refused, unlike install and rollback: an uninstall must stay possible whatever the recorded
    // manifest says.
    val hooks =
        try {
            recordedHooks(pkg)
        } catch (_: KetchError) {
            report.warn("${pkg.name}: skipping hooks from a ${pkg.origin.tier} manifest")
            null
        }
    hooks?.let { run(it, Event.BeforeUninstall, hookCtx) }
    platform.unplace(pkg.links, report)
    // The whole `store/<name>/` goes, not just the prefixes state knows of: a `.incoming` or `.old` sibling a failed
    // swap left behind would otherwise keep the package's folder alive forever.
    val packageDir = removePackageDir(cfg, pkg.name, report)
    (listOf(pkg.prefix) + pkg.retained.map { it.prefix })
        // Already attempted as part of the package folder; a second try would only repeat its warning.
        .filterNot { prefix -> packageDir != null && pathIsWithin(prefix, packageDir) }
        .forEach { removeStoreDir(cfg, it, report) }
    state.remove(pkg.name)
    hooks?.let { runOrWarn(it, Event.AfterUninstall, hookCtx) }
    // Recorded after the removal, for the same reason `commit` records last: the package is gone either way.
    record(cx, uninstallEvent(pkg, pkg.version.toString(), pkg.source.toString(), pkg.target.toString()))
    return pkg
}

/** Re-create links for an already-installed package. */
fun relink(
    cx: Ctx,
    state: State,
    name: String,
) {
    val pkg = state.installed(name)
    if (!isDir(pkg.prefix)) throw KetchError.EmptyPayload(pkg.prefix)
    val (links, binChoice) = replaceLinks(cx, host(), pkg, pkg.prefix, pkg.version.toString(), link = true)
    state.insert(pkg.copy(links = links, binChoice = binChoice))
}

/** Remove a package's links and keep its files, so `relink` can bring them back. */
fun unlink(
    cx: Ctx,
    state: State,
    name: String,
) {
    val pkg = state.installed(name)
    host().unplace(pkg.links, cx.report)
    state.insert(pkg.copy(links = emptyList()))
}

/** Hold a package at its version, or release it. Returns the package as state now records it. */
fun pin(
    state: State,
    name: String,
    pinned: Boolean,
): InstalledPackage = state.installed(name).copy(pinned = pinned).also(state::insert)

/**
 * Switch a package back to a retained version: the one before the current with no [to], otherwise the retained one
 * [to] names. The version left behind is retained in its place, so a second rollback undoes the first.
 */
fun rollback(
    cx: Ctx,
    state: State,
    name: String,
    to: String?,
): Installed {
    val cfg = cx.cfg
    val pkg = state.installed(name)
    if (pkg.pinned) throw KetchError.Pinned(pkg.name, pkg.version.toString())
    val idx = selectRetained(pkg, to)
    val target = pkg.retained[idx]
    if (!isDir(target.prefix)) throw KetchError.Msg("retained ${pkg.name} ${target.version} is missing from ${target.prefix}")

    val version = target.version.toString()
    val leaving = pkg.version.toString()
    // A rollback is an update back to the retained version, so it runs the update hooks — and, as at install, refuses
    // a manifest that may not run any before a single link moves.
    val hooks = recordedHooks(pkg)
    hooks?.let { run(it, Event.BeforeUpdate, Context(pkg.name, version, leaving, target.prefix, cfg.binDir, cfg.root, cx.report)) }

    val (links, binChoice) = replaceLinks(cx, host(), pkg, target.prefix, version, link = pkg.links.isNotEmpty())
    val retained = pkg.retained.toMutableList()
    retained.removeAt(idx)
    retained.removeAll { it.prefix == pkg.prefix || it.version == pkg.version }
    if (isDir(pkg.prefix) && isInsideStore(cfg.storeDir, pkg.prefix)) retained.add(0, RetainedVersion.fromInstalled(pkg))
    val rolled = pkg.rolledTo(target, links, retained, binChoice)
    state.insert(rolled)

    hooks?.let { runOrWarn(it, Event.AfterUpdate, Context(rolled.name, version, leaving, rolled.prefix, cfg.binDir, cfg.root, cx.report)) }
    record(cx, rollbackEvent(rolled, leaving, version, rolled.source.toString(), rolled.target.toString()))
    return Installed(rolled, pkg.version)
}

private fun InstalledPackage.rolledTo(
    target: RetainedVersion,
    links: List<LinkRecord>,
    retained: List<RetainedVersion>,
    binChoice: String?,
) = copy(
    version = target.version,
    prefix = target.prefix,
    sha256 = target.sha256,
    checksumVerified = target.checksumVerified,
    trust = target.trust,
    provenance = target.provenance,
    links = links,
    tag = target.tag,
    assetName = target.assetName,
    installedAt = nowUnix(),
    retained = retained,
    binChoice = binChoice,
)

/** The index of the retained version [to] names, or of the previous one without it. */
internal fun selectRetained(
    pkg: InstalledPackage,
    to: String?,
): Int {
    if (pkg.retained.isEmpty()) throw KetchError.NoRetained(pkg.name)
    if (to == null) return 0
    refuseCurrent(pkg, to)
    val available = pkg.retained.joinToString(", ") { it.version.toString() }
    return pkg.findRetained(to)?.index ?: throw KetchError.Msg("`${pkg.name}` has no retained version $to (retained: $available)")
}

/** Rolling back to the version already installed is no rollback. */
private fun refuseCurrent(
    pkg: InstalledPackage,
    to: String,
) {
    if (pkg.version.matchesRequest(to) || pkg.tag.eqIgnoreAsciiCase(to.rustTrim())) {
        throw KetchError.AlreadyInstalled(pkg.name, pkg.version.toString())
    }
}

/** Drop all but [keep] retained versions of a package, deleting their directories. Returns the versions dropped. */
fun prune(
    cx: Ctx,
    state: State,
    name: String,
    keep: UInt,
): List<Version> {
    val pkg = state.installed(name)
    val kept = mutableListOf<RetainedVersion>()
    val dropped = mutableListOf<Version>()
    for (previous in pkg.retained) {
        if (isDir(previous.prefix) && kept.size.toUInt() < keep) {
            kept += previous
            continue
        }
        // A state file written before retention dropped the live version can still list it; the entry goes, the
        // directory every link points into stays.
        if (entryKind(previous.prefix) != null && previous.prefix != pkg.prefix) removeStoreDir(cx.cfg, previous.prefix, cx.report)
        dropped += previous.version
    }
    state.insert(pkg.copy(retained = kept))
    return dropped
}

/**
 * Place [pkg] again from [payload], already in the store, and remove the links the new placement did not take over.
 * Placing first, like `commit`: a failed placement must not take the working links with it. Returns the new links and
 * the binary choice to remember.
 */
private fun replaceLinks(
    cx: Ctx,
    platform: Platform,
    pkg: InstalledPackage,
    payload: Path,
    version: String,
    link: Boolean,
): Pair<List<LinkRecord>, String?> {
    val cfg = cx.cfg
    val manifest = pkg.manifest
    val extras = extraPlacements(platform, manifest?.extraPaths.orEmpty())
    val remembered = pkg.binChoice
    val pick = if (link) pickBin(cx, platform, payload, manifest, pkg.name, Known(null, listOfNotNull(remembered))) else null
    val links =
        platform.place(
            Placement(
                name = pkg.name,
                version = version,
                // Placement is idempotent over its own output.
                payloadDir = payload,
                storeDir = payload,
                binDir = cfg.binDir,
                appsDir = cfg.appsDir,
                kind = manifest?.kind ?: PackageKind.Auto,
                binSpecs = pick?.specs ?: manifest?.bin.orEmpty(),
                // Lets the new links reclaim the destinations this package already owns.
                replacing = pkg.links,
                linkApps = cfg.linkApps,
                link = link,
                extras = extras,
            ),
        )
    unplaceStale(platform, pkg.name, pkg.links, links, cx.report)
    return links to rememberedChoice(pick, remembered)
}

/**
 * Remove the links in [old] that [links] did not take over. A failure leaves a dangling link, not a broken install,
 * so it is reported rather than propagated.
 */
internal fun unplaceStale(
    platform: Platform,
    name: String,
    old: List<LinkRecord>,
    links: List<LinkRecord>,
    report: Report,
) {
    val stale = old.filter { l -> links.none { it.link == l.link } }
    try {
        platform.unplace(stale, report)
    } catch (e: KetchError) {
        report.warn("could not remove old links for $name: ${e.message}")
    }
}
