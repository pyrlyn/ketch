package ketch.core.selfupdate

import fs.EntryKind
import fs.canon
import fs.canonicalize
import fs.copyFile
import fs.createDirAll
import fs.entryKind
import fs.removeAny
import fs.removeFile
import ketch.core.VERSION
import ketch.core.config.HOST_WINDOWS
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.extra.SelfDocs
import ketch.core.extra.generatedCompletionRel
import ketch.core.extra.plan
import ketch.core.extra.writeKetchDocs
import ketch.core.install.InstallRequest
import ketch.core.install.Installed
import ketch.core.install.install
import ketch.core.model.CompletionShell
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.model.PackageSpec
import ketch.core.model.Version
import ketch.core.platform.exposeExtras
import ketch.core.platform.host
import ketch.core.platform.rename
import ketch.core.platform.symlink
import ketch.core.report.Ctx
import ketch.core.source.ListOpts
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import kotlinx.io.files.Path

/*
 * Installing and updating ketch with ketch.
 *
 * ketch prefers to be one of its own packages: `self install` puts the running release into the store under the name
 * `ketch` and links it from the bin dir, exactly as `ketch install pyrlyn/ketch` would, so `list`, `history` and
 * `doctor` see it and `self upgrade` is an ordinary upgrade. A ketch copied flat into the bin dir by an older
 * installer is still updated in place.
 *
 * Either way this is deliberately stricter than a normal install: the running binary is the thing that verifies
 * every other download, so it is replaced only against a published checksum — never on trust-on-first-use — and, in
 * place, the previous binary is kept until the new one has proven it can run.
 */

/** Outcome of a self-update attempt. */
data class SelfUpdate(
    val from: Version,
    val to: Version,
    /** False when already current, or when `dryRun` was set. */
    val replaced: Boolean,
    val notes: String?,
)

/** The package name ketch records itself under. */
const val SELF_NAME = "ketch"

/** Channel label shown next to the package version in user-facing output. */
const val VERSION_CHANNEL = "preview"

/** Package version as `--version` and `self version` print it. */
fun displayVersion() = "$VERSION · $VERSION_CHANNEL"

/** The version this binary was built as. */
fun currentVersion() = Version.parse(VERSION)

/** The bootstrap binary name on this platform. */
internal fun bootstrapBinaryName() = if (HOST_WINDOWS) "ketch.exe" else SELF_NAME

/**
 * Where the running binary lives, with symlinks resolved so we replace the real file rather than the link pointing
 * at it.
 */
fun currentExe(): Path = canon(Path(process.currentExe() ?: throw KetchError.Msg("could not tell where this ketch is")))

/**
 * How `update` lists ketch's own releases. Without [pre] it asks for GitHub's latest release, as it always has; with
 * it, the newest release including prereleases, chosen by the same `selectRelease` every package goes through, which
 * also passes over the macOS app's `desktop-v*` tags.
 */
fun releaseOpts(pre: Boolean) = ListOpts(includePrerelease = pre)

/**
 * Install the running release of ketch as a package.
 *
 * The binary is fetched again from the release rather than copied from wherever this process happens to run: a copy
 * would be whatever the installer downloaded, and this path is the one that verifies it against the published
 * checksum. Throws [KetchError.AlreadyInstalled] when this version already is the package and [force] is off, like any
 * other install.
 */
fun installSelf(
    cx: Ctx,
    force: Boolean,
    linkDir: Path?,
    docs: SelfDocs,
): Installed =
    Lock.acquire(cx).use {
        // A previous in-place swap or flat install leaves its aside here. This process is a new one, so the file is no
        // longer the running image and the delete that failed at the end of that swap can succeed. A failure stays a
        // warning: the install can still move the flat binary aside.
        sweepStaleAsides(cx.cfg.binDir, cx.report)
        val state = State.load(cx.cfg)
        // Built-in sources only, as in `update`.
        val sources = SourceRegistry.builtinOnly(cx)
        val req = InstallRequest(PackageSpec.parse("${cx.cfg.selfRepo}@v${currentVersion()}"), force = force, requireChecksum = true)

        // A ketch copied flat into the bin dir is where the link now has to go, and the platform refuses to replace a
        // file ketch did not put there. It is moved aside rather than deleted so that a failed install still leaves a
        // ketch on PATH; the running image survives either way.
        val flat = Path(cx.cfg.binDir, bootstrapBinaryName())
        val aside = Path(cx.cfg.binDir, "$SELF_NAME.old").takeIf { entryKind(flat) == EntryKind.File }
        if (aside != null) fsKitAt(flat) { rename(flat, aside) }
        val result = runCatching { installAndRecord(cx, sources, state, req, linkDir, docs) }
        if (aside != null) {
            if (result.isSuccess) {
                runCatching { removeFile(aside) }
            } else {
                try {
                    rename(aside, flat)
                } catch (e: kotlinx.io.IOException) {
                    cx.report.warn("could not put $aside back (${e.message}); move it to $flat by hand")
                }
            }
        }
        result.getOrThrow()
    }

private fun installAndRecord(
    cx: Ctx,
    sources: SourceRegistry,
    state: State,
    req: InstallRequest,
    linkDir: Path?,
    docs: SelfDocs,
): Installed {
    val out =
        try {
            install(cx, sources, state, req)
        } catch (e: KetchError.AlreadyInstalled) {
            // Already the package: the bootstrap link and the docs are still this command's to put right.
            if (linkDir != null) recordBootstrapLink(cx, state, linkDir)
            try {
                exposeSelfDocs(cx, state, docs)
            } catch (docsError: KetchError) {
                cx.report.warn("could not install man page and completions: ${docsError.message}")
                throw e
            }
            try {
                state.save(cx.cfg)
            } catch (saveError: KetchError) {
                cx.report.warn("could not record man page and completions: ${saveError.message}")
            }
            throw e
        }
    if (linkDir != null) recordBootstrapLink(cx, state, linkDir)
    exposeSelfDocs(cx, state, docs)
    state.save(cx.cfg)
    return out
}

/** Whether a link record is the install.sh bootstrap outside the bin dir. */
private fun isBootstrapRecord(
    record: LinkRecord,
    binDir: Path,
): Boolean =
    record.target == Path(binDir, bootstrapBinaryName()) &&
        record.link.name == bootstrapBinaryName() &&
        record.link.parent?.let { parent -> runCatching { canonicalDir(parent) }.getOrNull() != binDir } == true

/** Resolve a directory the way install.sh does before comparing paths, creating it first. */
private fun canonicalDir(path: Path): Path {
    if (entryKind(path) == null) fsKitAt(path) { createDirAll(path) }
    return fsKitAt(path) { canonicalize(path) }
}

/**
 * Record a bootstrap link or copy outside `<root>/bin`, for install.sh.
 *
 * The link follows `<root>/bin/ketch` so `self upgrade` keeps the bootstrap path current. Uninstall removes it
 * through the package's link records.
 */
internal fun recordBootstrapLink(
    cx: Ctx,
    state: State,
    linkDir: Path,
) {
    val platform = host()
    val binDir = canonicalDir(cx.cfg.binDir)
    val dir = canonicalDir(linkDir)
    val target = Path(cx.cfg.binDir, bootstrapBinaryName())

    val old =
        state
            .get(SELF_NAME)
            ?.links
            .orEmpty()
            .filter { isBootstrapRecord(it, binDir) }
    if (old.isNotEmpty()) platform.unplace(old, cx.report)

    val kept = { pkg: ketch.core.model.InstalledPackage -> pkg.links.filterNot { isBootstrapRecord(it, binDir) } }
    if (dir == binDir) {
        state.get(SELF_NAME)?.let { state.insert(it.copy(links = kept(it))) }
        return
    }
    if (entryKind(canon(target)) != EntryKind.File) throw KetchError.Msg("$target is missing after install")

    val link = Path(dir, bootstrapBinaryName())
    clearBootstrapDestination(link, state.get(SELF_NAME)?.links.orEmpty())
    val record = createBootstrapLink(link, target)
    val entry = state.get(SELF_NAME) ?: throw KetchError.Msg("ketch package not installed")
    state.insert(entry.copy(links = kept(entry) + record))
}

private fun clearBootstrapDestination(
    link: Path,
    recorded: List<LinkRecord>,
) {
    if (entryKind(link) == null) return
    if (recorded.none { it.link == link }) {
        throw KetchError.Msg("$link already exists and was not installed by ketch for this package; move it aside first")
    }
    fsKitAt(link) { removeAny(link) }
}

private fun createBootstrapLink(
    link: Path,
    target: Path,
): LinkRecord {
    link.parent?.let { fsKitAt(it) { createDirAll(it) } }
    // Windows copies: a symlink there needs a privilege most users lack.
    val kind =
        if (HOST_WINDOWS) {
            fsKitAt(link) { copyFile(target, link) }
            LinkKind.CopiedFile
        } else {
            symlink(target, link)
            LinkKind.Symlink
        }
    return LinkRecord(link = link, target = target, kind = kind, role = LinkRole.Binary)
}

/** Generate ketch's man page and completions into the store prefix and link them into the user directories `doctor` reports. */
internal fun exposeSelfDocs(
    cx: Ctx,
    state: State,
    docs: SelfDocs,
) {
    val pkg = state.get(SELF_NAME) ?: return
    val platform = host()
    val extras = writeKetchDocs(pkg.prefix, docs)
    val planned = plan(extras, platform.userManRoot()) { platform.completionDir(it) }
    val owned = pkg.prefix.parent ?: pkg.prefix
    val extraLinks = exposeExtras(planned, pkg.prefix, owned, pkg.links)
    val fresh = extraLinks.map { it.link }.toSet()
    val stale = pkg.links.filter { it.role != LinkRole.Binary && it.link !in fresh }
    if (stale.isNotEmpty()) platform.unplace(stale, cx.report)
    // Windows loads neither directory by itself: a profile block and AutoRun are what switch completion on there.
    if (HOST_WINDOWS) {
        val powershellRel = generatedCompletionRel(CompletionShell.Powershell)
        enableWindowsCompletion(cx, planned.firstOrNull { it.relPath == powershellRel }?.dest)
    }
    val current = state.get(SELF_NAME) ?: return
    val links = current.links.filter { it.role == LinkRole.Binary || it.link in fresh }.toMutableList()
    for (link in extraLinks) if (links.none { it.link == link.link }) links += link
    state.insert(current.copy(links = links))
}

/** Install one shell's completion script the same way `self install` does. */
fun installCompletionScript(
    cx: Ctx,
    shell: CompletionShell,
    docs: SelfDocs,
) {
    val state = State.load(cx.cfg)
    if (state.get(SELF_NAME) == null) throw KetchError.Msg("ketch is not installed as a package; run `ketch self install` first")
    exposeSelfDocs(cx, state, docs)
    state.save(cx.cfg)
    cx.report.success("installed", "${shell.asStr} completions in ${host().completionDir(shell)}")
}
