// Port of cmd/import.rs: a package another manager knows, added the ketch way. The source's definition becomes a user
// manifest and is installed through the path `ketch install` takes; what changes on a re-run is decided in
// ketch.core.importer.decide, and this file only fetches, prints and commits.
package ketch.cmd

import cli.Matches
import fs.FsException
import fs.readText
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.io
import ketch.core.importer.Apply
import ketch.core.importer.BrewPick
import ketch.core.importer.Endpoints
import ketch.core.importer.Found
import ketch.core.importer.Have
import ketch.core.importer.HttpFetch
import ketch.core.importer.Step
import ketch.core.importer.decide
import ketch.core.importer.lookupBrew
import ketch.core.importer.lookupLinux
import ketch.core.importer.lookupWinget
import ketch.core.install.InstallRequest
import ketch.core.install.install
import ketch.core.manifest.userManifestPath
import ketch.core.manifest.writeManifest
import ketch.core.model.PackageSpec
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import ketch.ui.bareError
import ketch.ui.ctx
import ketch.ui.ctxAsking
import ketch.ui.note
import ketch.ui.out
import ketch.ui.success
import ketch.ui.warn
import kotlinx.io.files.Path

/** `ketch import`: look the package up in the catalogue the subcommand names, then add it. */
fun importPackage(
    cfg: Config,
    args: Matches,
) {
    val endpoints = Endpoints.fromEnv()
    val fetch = HttpFetch(ctx(cfg), endpoints)
    val command = checkNotNull(args.subcommand)
    val opts = command.matches
    val typed = checkNotNull(opts.value(if (command.name == "winget") "id" else "name"))
    val found =
        try {
            when (command.name) {
                "winget" -> lookupWinget(fetch, endpoints, typed)
                "brew" -> lookupBrew(fetch, endpoints, typed, brewPick(opts))
                else -> lookupLinux(fetch, endpoints, typed)
            }
        } catch (e: KetchError) {
            throw refusal(typed, e)
        }
    applyFound(cfg, opts, found)
}

private fun brewPick(opts: Matches) =
    when {
        opts.flag("cask") -> BrewPick.Cask
        opts.flag("formula") -> BrewPick.Formula
        else -> BrewPick.Either
    }

/**
 * A package that does not convert is the expected answer for most of every catalogue, not a fault in ketch: its
 * sentence is printed as it is, with no `error:` label or hint in front of it.
 */
internal fun refusal(
    typed: String,
    err: KetchError,
): KetchError {
    val text = err.message
    if (!text.startsWith("$typed can't be converted: ")) return err
    bareError(text)
    return KetchError.Reported(1)
}

private fun applyFound(
    cfg: Config,
    opts: Matches,
    found: Found,
) {
    val converted = found.converted
    found.notes.forEach(::note)
    val rendered = converted.render()
    val path = userManifestPath(cfg, converted.name)
    val label = path.toString()
    val source = "${converted.backend} ${converted.packageId}"
    val dryRun = opts.flag("dry_run")
    // Taken before the file is read, so two imports cannot both decide against the same old copy.
    (if (dryRun) null else Lock.acquire(ctx(cfg))).use {
        val state = State.load(cfg)
        val installed = state.get(converted.name)?.let { Have(it.version, it.tag) }
        val step = decide(converted, rendered, readIfExists(path), installed, cfg.target, label)
        if (step !is Apply) {
            success("up to date", "Everything is up to date: ${converted.name} ${converted.version} from $source")
            return
        }
        if (dryRun) {
            out(rendered.trimEnd())
            note(if (step.write != null) "would write $label" else "$label already holds this")
            note("would install ${converted.name} ${step.version}")
            return
        }
        if (step.unchecked) {
            warn("the $source definition records no checksum for this download; ketch checks it the way it checks any release")
        }
        step.write?.let {
            writeManifest(path, it)
            success("wrote", label)
        }
        val done = install(ctxAsking(cfg, !opts.flag("yes")), SourceRegistry.load(ctx(cfg)), state, request(cfg, converted.name, step))
        state.save(cfg)
        reportInstalled(done)
        pathHint(cfg, state)
    }
}

private fun readIfExists(path: Path): String? =
    try {
        readText(path)
    } catch (e: FsException) {
        if (e.kind != FsException.Kind.NotFound) throw KetchError.io(path, e)
        null
    }

private fun request(
    cfg: Config,
    name: String,
    plan: Apply,
) = InstallRequest(
    spec = PackageSpec.parse(name).copy(version = plan.version),
    force = plan.force,
    requireChecksum = cfg.requireChecksums,
    assetOverride = plan.asset,
    expectedSha256 = plan.sha256,
    // The version is the one the source names; asking whether to take a newer one would be a different command.
    offerUpdate = false,
)
