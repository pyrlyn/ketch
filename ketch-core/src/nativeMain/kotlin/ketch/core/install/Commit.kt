package ketch.core.install

import ketch.core.binchoice.Known
import ketch.core.binchoice.Picked
import ketch.core.binchoice.checkFlag
import ketch.core.binchoice.choose
import ketch.core.binchoice.commandName
import ketch.core.binchoice.contenders
import ketch.core.error.KetchError
import ketch.core.extra.ExtraPlacement
import ketch.core.hooks.Context
import ketch.core.hooks.Event
import ketch.core.hooks.allowed
import ketch.core.hooks.refusal
import ketch.core.hooks.run
import ketch.core.hooks.runOrWarn
import ketch.core.manifest.userManifestPath
import ketch.core.manifest.writeBins
import ketch.core.model.BinSpec
import ketch.core.model.Channel
import ketch.core.model.ExtraPath
import ketch.core.model.InstalledPackage
import ketch.core.model.LinkRecord
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageKind
import ketch.core.model.RetainedVersion
import ketch.core.model.nowUnix
import ketch.core.model.rustComponents
import ketch.core.platform.Placement
import ketch.core.platform.Platform
import ketch.core.platform.host
import ketch.core.platform.pathIsWithin
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.report.Stage
import ketch.core.state.State
import ketch.core.stats.installEvent
import ketch.core.stats.record
import kotlinx.io.files.Path

/**
 * Commit a prepared install to the install tree and record it in [state].
 *
 * The payload is placed, links are created or updated, replaced content is retired, and the package is stored in
 * [state]. Failures while placing are thrown; cleanup and statistics failures are reported without invalidating a
 * successful install. Closes [prepared] either way.
 */
fun commit(
    cx: Ctx,
    state: State,
    prepared: Prepared,
): Installed =
    prepared.use {
        // Before the first hook or file: later steps are short and leave the tree consistent on their own, so this
        // is the last point a stop is free.
        prepared.cancel.check()
        cx.report.stage(prepared.label, Stage.Installing)
        // Read again rather than trusting what `prepare` saw: in a batch, another package may have been placed since.
        Commit(cx, prepared, host(), state.get(prepared.manifest.name)).run(state)
    }

/** One [commit], holding what each of its steps reads. */
private class Commit(
    val cx: Ctx,
    val prepared: Prepared,
    val platform: Platform,
    val existing: InstalledPackage?,
) {
    val cfg = cx.cfg
    val name = prepared.manifest.name
    val version = prepared.release.version.toString()
    val storeDir = cfg.packageDir(name, version)

    /** A reinstall of the same version is an install, not an update: nothing the update hooks exist to migrate has changed. */
    val previous = existing?.takeIf { it.version != prepared.release.version }?.version?.toString()

    fun run(state: State): Installed {
        // Before the hooks and before anything is placed: a leftover the sweep cannot remove is a reason to stop,
        // not to install beside it.
        sweepSwapLeftovers(cfg, name, existing)
        val manifest = prepared.manifest
        // Refused before anything is placed: a manifest that may not run hooks must not install as though it had
        // none, or the user never learns why the hook they wrote did nothing.
        if (!manifest.hooks.isEmpty() && !allowed(prepared.origin)) throw refusal(prepared.origin, name)
        run(manifest.hooks, if (previous != null) Event.BeforeUpdate else Event.BeforeInstall, hookContext(storeDir))

        // Reinstalling the same version writes into the directory the current install already occupies, and failing
        // there must not delete it. A retained copy of this version is the same: placement restores it on failure,
        // and deleting it afterwards would lose the rollback target.
        val inPlace = existing != null && (existing.prefix == storeDir || existing.retained.any { it.prefix == storeDir })
        val pkg =
            ScopedDir(storeDir.takeIf { !inPlace }).use { orphan ->
                placed(manifest).also { pkg ->
                    state.insert(pkg)
                    orphan.keep()
                }
            }

        pkg.manifest?.let { runOrWarn(it.hooks, if (previous != null) Event.AfterUpdate else Event.AfterInstall, hookContext(pkg.prefix)) }

        // Everything above has already happened: the payload is placed and `state` names it. Recording is the last
        // and least important thing, so it warns rather than throws.
        val replaced = existing?.version
        val elapsed = prepared.started.elapsedNow().inWholeMilliseconds
        val duration = elapsed.takeIf { it <= Int.MAX_VALUE }?.toInt()
        record(cx, installEvent(pkg, replaced?.toString(), duration, version, pkg.source.toString(), pkg.target.toString()))
        return Installed(pkg, replaced)
    }

    /** Place the payload, retire what it replaced and build the record state keeps. */
    private fun placed(start: Manifest): InstalledPackage {
        var manifest = start
        val extras = extraPlacements(platform, manifest.extraPaths)
        val remembered = existing?.binChoice
        val known = Known(prepared.bin, listOfNotNull(remembered, prepared.lockedBin))
        val pick = if (prepared.link || known.flag != null) pickBin(cx, platform, prepared.payload, manifest, name, known) else null
        val links = platform.place(placement(manifest, pick?.specs ?: manifest.bin, extras))
        prepared.close()

        val retained = existing?.retained.orEmpty().toMutableList()
        existing?.let { old -> retire(old, links, retained) }
        val origin = prepared.origin
        if (pick != null && origin is ManifestOrigin.User) manifest = recordInManifest(origin.path, manifest, pick, cx.report)
        return record(manifest, links, retained, rememberedChoice(pick, remembered))
    }

    private fun placement(
        manifest: Manifest,
        binSpecs: List<BinSpec>,
        extras: List<ExtraPlacement>,
    ) = Placement(
        name = name,
        version = version,
        payloadDir = prepared.payload,
        storeDir = storeDir,
        binDir = cfg.binDir,
        appsDir = cfg.appsDir,
        kind = manifest.kind,
        binSpecs = binSpecs,
        replacing = existing?.links.orEmpty(),
        linkApps = cfg.linkApps,
        link = prepared.link,
        extras = extras,
    )

    /** Remove [old]'s links the new version did not take over, and keep its prefix for rollback. */
    private fun retire(
        old: InstalledPackage,
        links: List<LinkRecord>,
        retained: MutableList<RetainedVersion>,
    ) {
        unplaceStale(platform, old.name, old.links, links, cx.report)
        retainReplaced(cfg, old, storeDir, prepared.release.version, retained)
    }

    private fun record(
        manifest: Manifest,
        links: List<LinkRecord>,
        retained: List<RetainedVersion>,
        binChoice: String?,
    ) = InstalledPackage(
        name = name,
        version = prepared.release.version,
        source = manifest.source,
        tag = prepared.release.tag,
        target = platform.target(),
        assetName = prepared.assetName,
        sha256 = prepared.sha256,
        checksumVerified = prepared.checksumVerified,
        installedAt = nowUnix(),
        prefix = storeDir,
        links = links,
        pinned = existing?.pinned == true,
        origin = prepared.origin,
        manifest = manifest,
        localKind = prepared.localKind,
        localPath = prepared.localPath,
        trust = prepared.trust,
        retained = retained,
        provenance = prepared.provenance,
        binChoice = binChoice,
        channel = prepared.channel,
        draft = prepared.release.draft,
        publishedAt = prepared.release.publishedAt.takeIf { prepared.channel == Channel.Dev },
    )

    private fun hookContext(prefix: Path) = Context(name, version, previous, prefix, cfg.binDir, cfg.root, cx.report)
}

/** The binary chosen for a package whose manifest names none, out of several in its payload that share the package's name. */
internal class BinPick(
    /**
     * What placement links in place of everything it would discover: the chosen binary and every executable outside
     * its family, in discovery order. Only the family members that lost are left out.
     */
    val specs: List<BinSpec>,
    /** The chosen file's name, as state remembers it. */
    val file: String,
    val how: Picked,
)

/**
 * Decide which binary [payload] links when the manifest names none and more than one executable answers to [name] —
 * before anything is placed, so the choice is made once and by binchoice's fixed order rather than by the order this
 * platform's discovery sorts files in. Null leaves placement to its usual rules. A `--bin` in [known] is checked
 * either way, so a name that matches nothing is an error, not a silent no-op.
 */
internal fun pickBin(
    cx: Ctx,
    platform: Platform,
    payload: Path,
    manifest: Manifest?,
    name: String,
    known: Known,
): BinPick? {
    val listed = manifest != null && manifest.bin.isNotEmpty()
    if (listed && known.flag != null) {
        throw KetchError.Msg(
            "--bin `${known.flag}`: the manifest for `$name` already names the binaries it links; change its `bin` instead",
        )
    }
    // A manifest that names its binaries leaves nothing to choose.
    val found = if (listed) emptyList() else platform.binCandidates(payload, manifest?.kind ?: PackageKind.Auto, name)
    val files = found.map { it.name }
    val rivals = contenders(name, files)
    if (rivals.isEmpty()) {
        known.flag?.let { checkFlag(it, files) }
        return null
    }
    val hint = userManifestPath(cx.cfg, name).toString()
    val (i, how) = choose(name, rivals.map { files[it] }, known, { cx.decider.chooseBinary(name, it) }, hint)
    val chosen = rivals[i]
    // The exact paths, not globs or bare names: a release can carry a completion script or a man page with the same
    // file name elsewhere.
    val specs =
        found.indices
            .filter { it == chosen || it !in rivals }
            .map { BinSpec(path = relativeTo(found[it], payload), name = files[it]) }
    return BinPick(specs, files[chosen], how)
}

/** [path] below [root], `/`-joined, as Rust's `strip_prefix`; [path] itself when it is not below. */
private fun relativeTo(
    path: Path,
    root: Path,
): String =
    if (pathIsWithin(path, root)) {
        rustComponents(path).drop(rustComponents(root).size).joinToString("/")
    } else {
        path.toString()
    }

/**
 * What state remembers after a pick: a choice somebody made — now or before — and otherwise whatever it remembered
 * already, for the next release that needs it. An exact name match is not a choice and is not recorded.
 */
internal fun rememberedChoice(
    pick: BinPick?,
    remembered: String?,
): String? = pick?.takeIf { it.how != Picked.Exact }?.file ?: remembered

/**
 * Write a pick into the user manifest it came from, so the file names its binaries from now on: the chosen one and
 * every other it links. Best effort: the install has already succeeded, and state remembers the choice even when the
 * file cannot be written. Returns the manifest as it now reads.
 */
internal fun recordInManifest(
    path: Path,
    manifest: Manifest,
    pick: BinPick,
    report: Report,
): Manifest {
    val commands = pick.specs.mapNotNull { it.name }.map(::commandName)
    val written =
        try {
            writeBins(path, manifest.name, commands)
        } catch (e: KetchError) {
            report.warn("could not record the chosen binary in $path: ${e.message}")
            false
        }
    if (!written) return manifest
    report.note("$path now names its binaries: bin = [${commands.joinToString(", ") { "{ name = \"$it\" }" }}]")
    return manifest.copy(bin = commands.map { BinSpec(path = null, name = it) })
}

internal fun extraPlacements(
    platform: Platform,
    extraPaths: List<ExtraPath>,
): List<ExtraPlacement> = ketch.core.extra.plan(extraPaths, platform.userManRoot()) { platform.completionDir(it) }
