package ketch.core.install

import fs.TempDir
import fs.createDirAll
import fs.joinPath
import ketch.core.config.Config
import ketch.core.config.sanitizeComponent
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.extract.extractAuto
import ketch.core.extract.unwrapSingleDir
import ketch.core.manifest.Resolver
import ketch.core.manifest.sameSource
import ketch.core.model.BinSpec
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.LocalKind
import ketch.core.model.Manifest
import ketch.core.model.Provenance
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.VersionSpec
import ketch.core.model.normalizeName
import ketch.core.platform.Platform
import ketch.core.platform.host
import ketch.core.report.Ctx
import ketch.core.report.ProgressSink
import ketch.core.report.Stage
import ketch.core.resolve.ScoredAsset
import ketch.core.resolve.listOpts
import ketch.core.resolve.scoreAssets
import ketch.core.source.SourceRegistry
import ketch.core.source.classify
import ketch.core.source.copyTree
import ketch.core.source.packageRef
import ketch.core.source.resolvePath
import ketch.core.source.sha256Tree
import ketch.core.state.State
import ketch.core.trust.refuse
import ketch.core.trust.verify
import kotlinx.io.files.Path
import kotlin.time.TimeSource

/** Run the pipeline. Mutates [state] in memory; the caller saves it, so a batch install writes `state.json` once. */
fun install(
    cx: Ctx,
    sources: SourceRegistry,
    state: State,
    req: InstallRequest,
): Installed = cx.report.download(req.spec.label()).use { progress -> commit(cx, state, prepare(cx, sources, state, req, progress)) }

/**
 * Resolve, download, verify and unpack a package, without touching the install tree. The result goes to [commit]
 * once every requested package is prepared.
 */
fun prepare(
    cx: Ctx,
    sources: SourceRegistry,
    state: State,
    req: InstallRequest,
    progress: ProgressSink,
): Prepared {
    req.cancel.check()
    val started = TimeSource.Monotonic.markNow()
    val platform = host()
    val label = req.spec.label()
    cx.report.stage(label, Stage.Resolving)
    val (resolved, origin) = Resolver.load(cx).resolve(req.spec)
    val local = localOf(resolved)
    val manifest = named(local?.let { resolved.copy(source = packageRef(it.second)) } ?: resolved, req, local)
    val source = sources.forRef(manifest.source)
    val opts =
        listOpts(cx.cfg, manifest, req.prerelease).let {
            if (req.draft) it.askForDrafts(source, manifest.source.scheme) else it
        }
    cx.report.step("resolving", "${manifest.name} (${manifest.source})")
    val release = cx.report.activity("resolving ${manifest.name}").run { source.resolve(manifest.source.id, req.spec.version, opts) }
    checkPublished(req, manifest.name, release)

    // Nothing is downloaded until we know the install is actually wanted.
    val existing = state.get(manifest.name)
    existing?.let { checkWanted(req, it, manifest, release) }

    val chosen = chooseAsset(cx.cfg, platform, release, manifest, req).also { said(cx, it) }

    fsKitAt(cx.cfg.cacheDir) { createDirAll(cx.cfg.cacheDir) }
    val unpack = fsKitAt(cx.cfg.cacheDir) { TempDir.createIn(cx.cfg.cacheDir) }
    var prepared: Prepared? = null
    try {
        val job = Job(cx, req, label, manifest, release, unpack.path, progress)
        val payload =
            if (local?.first == LocalKind.App) {
                job.copyApp(local.second)
            } else {
                job.download(platform, source, chosen.asset.let { a -> local?.let { a.copy(url = it.second.toString()) } ?: a }, existing)
            }
        cx.report.stage(label, Stage.Trusting)
        val trust = checkTrust(platform, cx, payload.root, manifest.name)
        prepared =
            Prepared(
                label = label,
                manifest = manifest,
                origin = origin,
                release = release,
                assetName = payload.assetName,
                sha256 = payload.sha256,
                checksumVerified = payload.checksumVerified,
                trust = trust,
                provenance = payload.provenance,
                link = req.link,
                bin = req.bin,
                lockedBin = req.lockedBin,
                payload = payload.root,
                unpack = unpack,
                started = started,
                localKind = local?.first,
                localPath = local?.second,
                cancel = req.cancel,
                // Only the request's own opt-in, not `config.toml` or the manifest: both are read again on every
                // `upgrade`, so recording them here would keep a package on prereleases after the person switched them off.
                channel = Channel.of(release, req.prerelease, req.draft),
            )
        return prepared
    } finally {
        // The unpacked payload belongs to the result once there is one; a failure on the way deletes it.
        if (prepared == null) unpack.close()
    }
}

/**
 * The kind and absolute path of a `local:` package. Recorded absolute so list and info survive a later change of
 * working directory; classification also needs the path to exist before anything is copied.
 */
private fun localOf(manifest: Manifest): Pair<LocalKind, Path>? {
    if (manifest.source.scheme != "local") return null
    val abs = resolvePath(manifest.source.id)
    return classify(abs) to abs
}

/** [manifest] under the request's `--name`, and a local single file's binary named for the package. */
private fun named(
    manifest: Manifest,
    req: InstallRequest,
    local: Pair<LocalKind, Path>?,
): Manifest {
    var out = manifest
    req.nameOverride?.let { raw ->
        val name = sanitizeComponent(normalizeName(raw))
        if (name.isEmpty()) throw KetchError.Msg("--name produced an empty package name")
        out = out.copy(name = name).also { it.validate() }
    }
    // A local single file should appear on PATH under the package name, not whatever the file happened to be
    // called on disk (`a.out`, a symlink leaf, a version-stamped build artifact).
    val single = local?.takeIf { it.first == LocalKind.Binary || it.first == LocalKind.Symlink }?.second
    if (single != null && out.bin.isEmpty()) {
        val leaf = single.name.ifEmpty { out.name }
        // Spelled the way the download is staged, or a leaf that sanitizing changes (`.tool`, `-tool`) is never found.
        out = out.copy(bin = listOf(BinSpec(path = sanitizeComponent(leaf), name = out.name)))
    }
    return out
}

/** Refuse an install of [old]'s name that is not wanted: another source, a pin, nothing new, or an update to ask about. */
private fun checkWanted(
    req: InstallRequest,
    old: InstalledPackage,
    manifest: Manifest,
    release: Release,
) {
    // State is keyed by name alone. Another project under the same name would take over this one's links, run its
    // update hooks and mix two sources' versions in one retained list — a replacement nobody asked for by name.
    if (!sameSource(old.source, manifest.source)) {
        throw KetchError.Msg(
            "`${old.name}` is already installed from ${old.source}; ${manifest.source} would replace it. " +
                "Install it under another name with --name, or uninstall `${old.name}` first",
        )
    }
    val unversioned = req.spec.version !is VersionSpec.Exact
    if (old.pinned && unversioned) throw KetchError.Pinned(old.name, old.version.toString())
    checkNews(req, old, release, unversioned)
}

/** Refuse a release that is the installed one, or one to ask about first. */
private fun checkNews(
    req: InstallRequest,
    old: InstalledPackage,
    release: Release,
    unversioned: Boolean,
) {
    // The same floating tag can name a new build, which is not "already installed" however equal the tags are.
    val sameBuild = old.channel != Channel.Dev || !rebuilt(InstalledBuild.of(old), release)
    if (old.tag == release.tag && sameBuild && !req.force) {
        // `pkg@1.2.0` asked for that version and keeps the old message.
        throw if (unversioned && req.offerUpdate) {
            KetchError.NoUpdate(old.name, old.version.toString())
        } else {
            KetchError.AlreadyInstalled(old.name, old.version.toString())
        }
    }
    if (unversioned && req.offerUpdate && !req.force) {
        throw KetchError.UpdateAvailable(old.name, old.version.toString(), release.version.toString(), release.tag)
    }
}

/** What either kind of payload leaves for [Prepared]. */
private class Payload(
    val sha256: String,
    val assetName: String,
    val checksumVerified: Boolean,
    val provenance: Provenance?,
    val root: Path,
)

/** The steps that bring one payload into [unpack], sharing what every one of them reads. */
private class Job(
    val cx: Ctx,
    val req: InstallRequest,
    val label: String,
    val manifest: Manifest,
    val release: Release,
    val unpack: Path,
    val progress: ProgressSink,
) {
    /** Local `.app` bundles are directories: copy the tree rather than pretending they are a downloadable archive. */
    fun copyApp(app: Path): Payload {
        cx.report.stage(label, Stage.Downloading)
        val destName = app.name.ifEmpty { "App.app" }
        val dest = joinPath(unpack, destName)
        copyTree(app, dest)
        // The copy is hashed, not the original, so the digest describes exactly what gets placed. Like a local file,
        // a bundle has no published checksum to require, but a lockfile's hash still holds it.
        val sha256 = sha256Tree(dest)
        progress.finish("copied")
        cx.report.stage(label, Stage.Verifying)
        checkLocked(req, manifest.name, destName, sha256)
        // A bundle on disk has no release to carry a signature, so a policy that requires one cannot be met.
        val provenance = manifest.trust?.let { refuse(it, destName, "a local app bundle has no published signature", cx.report) }
        return Payload(sha256, destName, false, provenance, payloadRoot(unpack, manifest.stripPrefix))
    }

    fun download(
        platform: Platform,
        source: ketch.core.source.Source,
        asset: ReleaseAsset,
        existing: InstalledPackage?,
    ): Payload {
        cx.report.stage(label, Stage.Downloading)
        // A directory of its own, not a name under the cache. Two `prepare`s run side by side, and an alias and a
        // repo path naming the same package would pick the same file name: they would overwrite each other's
        // archive. The archive is staging, never a cache, so a unique directory costs nothing.
        val cache = cx.cfg.cacheDir
        return fsKitAt(cache) { TempDir.createIn(cache) }.use { staging ->
            val downloadPath = joinPath(staging.path, sanitizeComponent(asset.name))
            val sha256 = source.download(asset, downloadPath, progress, req.cancel)
            checkLocked(req, manifest.name, asset.name, sha256)

            cx.report.stage(label, Stage.Verifying)
            // Local packages never publish a checksum; requiring one would make every `local:` install fail for a
            // reason the user cannot fix.
            val require = manifest.source.scheme != "local" && (req.requireChecksum || cx.cfg.requireChecksums)
            val verified = verifyChecksum(source, manifest.source.id, release, asset, sha256, require, cx.report)
            refuseChecksumDowngrade(req, existing, verified, asset)

            // Before extraction: the unpacker is the widest attack surface ketch has, and a file its publisher did
            // not vouch for need not reach it.
            val provenance = verify(manifest.trust, source, release, asset, downloadPath, sha256, staging.path, cx.report)

            cx.report.stage(label, Stage.Extracting)
            val format =
                cx.report.activity("extracting ${asset.name}").run {
                    extractAuto(downloadPath, unpack, platform.extractors(), cx.report)
                }
            cx.report.debug("unpacked ${asset.name} as $format")
            Payload(sha256, asset.name, verified, provenance, payloadRoot(unpack, manifest.stripPrefix))
        }
    }
}

/** The asset `--asset` names, or the best one this platform can run. */
internal fun chooseAsset(
    cfg: Config,
    platform: Platform,
    release: Release,
    manifest: Manifest,
    req: InstallRequest,
): ScoredAsset {
    req.assetOverride?.let { wanted ->
        val asset =
            release.assets.firstOrNull { it.name == wanted }
                ?: throw KetchError.Msg("release `${release.tag}` has no asset named `$wanted`")
        return ScoredAsset(asset, ketch.core.platform.AssetScore(Int.MAX_VALUE, cfg.target.arch, false, "chosen with --asset"))
    }
    return scoreAssets(cfg, platform, release, manifest.asset).firstOrNull()
        ?: throw KetchError.NoCompatibleAsset(manifest.source.toString(), release.tag, cfg.target.toString())
}

/** Say which asset won and why, and warn when it will only run under emulation. */
private fun said(
    cx: Ctx,
    chosen: ScoredAsset,
) {
    cx.report.debug("selected ${chosen.asset.name} — ${chosen.score.reason}")
    if (chosen.score.emulated) cx.report.warn("${chosen.asset.name} is an ${chosen.score.arch} build and will run under emulation")
}

/** Apply `strip_prefix`, or unwrap the single wrapper directory most tarballs use, so the root is where the files are. */
internal fun payloadRoot(
    unpacked: Path,
    strip: ULong?,
): Path {
    if (strip == null || strip == 0uL) return unwrapSingleDir(unpacked)
    var root = unpacked
    repeat(strip.toInt()) { root = unwrapSingleDir(root) }
    return root
}
