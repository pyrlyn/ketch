package ketch.core.selfupdate

import fs.EntryKind
import fs.FsException
import fs.TempDir
import fs.copyFile
import fs.createDirAll
import fs.joinPath
import fs.removeFile
import fs.walk
import ketch.core.config.sanitizeComponent
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.error.io
import ketch.core.extra.SelfDocs
import ketch.core.extract.extractAuto
import ketch.core.install.InstallRequest
import ketch.core.install.install
import ketch.core.install.verifyChecksum
import ketch.core.model.AssetSelector
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.VersionSpec
import ketch.core.platform.ensureExecutable
import ketch.core.platform.host
import ketch.core.platform.rename
import ketch.core.process.stoppedDetail
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.resolve.scoreAssets
import ketch.core.source.Source
import ketch.core.source.SourceRegistry
import ketch.core.state.Lock
import ketch.core.state.State
import kotlinx.io.files.Path
import platform.posix.usleep
import process.Command
import process.Output
import process.ProcessException
import process.runBounded
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long the freshly copied binary gets to prove it can start.
 *
 * Antivirus software commonly inspects a new executable before allowing it to start; that pause is seconds, so a probe
 * that outlives this means the binary cannot start and the previous one goes back, instead of the upgrade — and the
 * terminal running it — waiting forever.
 */
private val VERIFY_TIMEOUT = 60.seconds

/** What the `--version` probe may print. */
private const val PROBE_OUTPUT = 1L shl 16

/** How long to wait between attempts when a swap hits a transient file lock. */
internal val SWAP_PAUSES = listOf(100.milliseconds, 150.milliseconds, 200.milliseconds, 250.milliseconds)

/** Windows `ERROR_ACCESS_DENIED` and `ERROR_SHARING_VIOLATION`, compared on every OS as Rust's `raw_os_error` check is. */
private val LOCK_CODES = setOf(5, 32)

/**
 * Fetch the latest ketch release and install it: as an upgrade of the `ketch` package when there is one, otherwise by
 * replacing this binary in place. [pre] widens "latest" to prereleases.
 */
fun update(
    cx: Ctx,
    force: Boolean,
    dryRun: Boolean,
    pre: Boolean,
    docs: SelfDocs,
): SelfUpdate =
    Lock.acquire(cx).use {
        val state = State.load(cx.cfg)
        // When ketch is a package, the package is what gets updated, so its version is the one that counts — not this
        // binary's, which a Homebrew upgrade or a fresh install.sh may already have moved ahead of the store.
        val installed = state.get(SELF_NAME)?.version
        val from = installed ?: currentVersion()

        // With no package, the upgrade would rewrite this binary in place. mise names the version directory after the
        // release it unpacked, so that would leave `mise ls` and `mise upgrade` believing in a version that is no
        // longer the one on disk. Refused before any network call, dry run included, so the two never disagree about
        // what would happen.
        if (installed == null) {
            miseToolDir()?.let { tool ->
                throw KetchError.Msg(
                    "this ketch is managed by mise ($tool): upgrade it with `mise upgrade`, or run `ketch self install` to let ketch manage itself",
                )
            }
        }

        // Built-in sources only: a third-party plugin must never be in a position to hand ketch its own replacement.
        val sources = SourceRegistry.builtinOnly(cx)
        val source = sources.get("github")
        cx.report.step("checking", cx.cfg.selfRepo)
        val release = cx.report.activity("checking for updates").run { source.resolve(cx.cfg.selfRepo, VersionSpec.Latest, releaseOpts(pre)) }
        val to = release.version

        if (to <= from && !force) {
            if (!dryRun) sweepStaleAsides(cx.cfg.binDir, cx.report)
            return@use SelfUpdate(from, to, replaced = false, notes = null)
        }
        if (dryRun) return@use SelfUpdate(from, to, replaced = false, notes = release.notes)
        sweepStaleAsides(cx.cfg.binDir, cx.report)

        if (installed != null) {
            val req = InstallRequest(PackageSpec.parse("${cx.cfg.selfRepo}@${release.tag}"), force = force, requireChecksum = true)
            install(cx, SourceRegistry.builtinOnly(cx), state, req)
            exposeSelfDocs(cx, state, docs)
            state.save(cx.cfg)
        } else {
            replaceInPlace(cx, source, release)
        }
        SelfUpdate(from, to, replaced = true, notes = release.notes)
    }

/** Download, verify and unpack [release], then swap it in for the running binary. */
private fun replaceInPlace(
    cx: Ctx,
    source: Source,
    release: Release,
) {
    val (cfg, report) = cx.cfg to cx.report
    val platform = host()
    val chosen =
        scoreAssets(cfg, platform, release, AssetSelector()).firstOrNull()
            ?: throw KetchError.NoCompatibleAsset(cfg.selfRepo, release.tag, cfg.target.toString())

    fsKitAt(cfg.cacheDir) { createDirAll(cfg.cacheDir) }
    fsKitAt(cfg.cacheDir) { TempDir.createIn(cfg.cacheDir) }.use { work ->
        // The asset name is the release author's string, not ketch's. It reaches a path here, so it goes through the
        // same guard every other asset name does.
        val download = joinPath(work.path, sanitizeComponent(chosen.asset.name))
        val sha256 = report.download("download").use { progress -> source.download(chosen.asset, download, progress, cx.cancel) }

        // `require` is hard-coded: for its own binary ketch does not accept the trust-on-first-use path it allows for
        // packages.
        report.activity("verifying").run { verifyChecksum(source, cfg.selfRepo, release, chosen.asset, sha256, true, report) }

        val unpacked = joinPath(work.path, "payload")
        report.activity("extracting").run { extractAuto(download, unpacked, platform.extractors(), report) }
        val fresh = findBinary(unpacked)

        val exe = currentExe()
        report.activity("replacing").run { replaceBinary(exe, fresh, report) }
    }
}

/** Swap [fresh] into [exe], keeping the old binary until the new one has shown it can run. A ketch that cannot start is a ketch that cannot fix itself. */
private fun replaceBinary(
    exe: Path,
    fresh: Path,
    report: Report,
) {
    val backup = swapBackup(exe)
    // The previous swap's aside is this rename's destination. On Windows that delete fails at the end of the swap,
    // because this process is the image just renamed onto it; by the next swap that process has exited.
    sweepAside(backup, report)
    // Rename rather than overwrite: the running image stays valid, and a failed copy leaves something to put back. A
    // short antivirus lock is retried; once those pauses are spent the error is returned as before.
    fsKitAt(exe) { ioRetry({ rename(exe, backup) }, ::pause) }

    fun restore(detail: KetchError): KetchError {
        runCatching { ioRetry({ removeFile(exe) }, ::pause) }
        return try {
            ioRetry({ rename(backup, exe) }, ::pause)
            detail
        } catch (e: FsException) {
            KetchError.Msg("${detail.message}; could not restore the previous binary (${e.detail}): move $backup back to $exe by hand")
        }
    }

    // Copy, not rename: the download lives in the cache dir, which may be on a different filesystem.
    try {
        ioRetry({ copyFile(fresh, exe) }, ::pause)
        ensureExecutable(exe)
        probeRuns(exe, VERIFY_TIMEOUT)
    } catch (e: FsException) {
        throw restore(KetchError.io(exe, e))
    } catch (e: KetchError) {
        throw restore(e)
    }
    // Silent on purpose. On Windows this process is the image at `backup` until it exits, so this delete fails for the
    // whole process and a warning here would fire on every successful upgrade. The next self command sweeps it, and
    // warns only then.
    runCatching { removeFile(backup) }
}

private fun pause(delay: Duration) {
    usleep(delay.inWholeMicroseconds.toUInt())
}

/** Where `replaceBinary` parks the running image (`ketch.exe` → `ketch.exe.old`). */
internal fun swapBackup(exe: Path): Path = joinPath(exe.parent ?: Path("."), "${exe.name.ifEmpty { SELF_NAME }}.old")

/**
 * Asides a self command may have left next to [binary].
 *
 * `replaceBinary` appends `.old` to the whole file name. `installSelf` replaces the extension, which on Windows turns
 * `ketch.exe` into `ketch.old`.
 */
internal fun asideCandidates(binary: Path): List<Path> {
    val swap = swapBackup(binary)
    // Rust's `file_stem`: a leading dot starts a name, not an extension.
    val stem =
        binary.name
            .lastIndexOf('.')
            .takeIf { it > 0 }
            ?.let { binary.name.substring(0, it) } ?: binary.name
    val flat = joinPath(binary.parent ?: Path("."), "$stem.old")
    return if (flat == swap) listOf(swap) else listOf(swap, flat)
}

/** Delete one previous aside. Missing is the usual case and not an error. */
internal fun sweepAside(
    path: Path,
    report: Report,
) {
    try {
        removeFile(path)
    } catch (e: FsException) {
        if (e.kind != FsException.Kind.NotFound) {
            report.warn("could not remove previous binary $path (${e.detail}); the next self-update tries again")
        }
    }
}

/** Delete asides sitting in the bin dir before a self command changes anything. */
internal fun sweepStaleAsides(
    binDir: Path,
    report: Report,
) {
    for (path in asideCandidates(joinPath(binDir, bootstrapBinaryName()))) sweepAside(path, report)
}

/** `PermissionDenied`, plus Windows' two lock codes: what antivirus real-time protection returns while it holds a file for a few milliseconds. */
internal fun isTransientLock(e: FsException) = e.kind == FsException.Kind.PermissionDenied || e.errno in LOCK_CODES

/** Run [op] again after each pause in [SWAP_PAUSES] when the error is a transient lock. Any other error is thrown on the first attempt. */
internal fun <T> ioRetry(
    op: () -> T,
    pause: (Duration) -> Unit,
): T {
    val pauses = SWAP_PAUSES.iterator()
    while (true) {
        try {
            return op()
        } catch (e: FsException) {
            if (!isTransientLock(e) || !pauses.hasNext()) throw e
            pause(pauses.next())
        }
    }
}

/**
 * Run `exe --version`, stopping it after [timeout]. A child that starts and never exits is stopped with its process
 * group, so it cannot keep holding the new binary.
 */
internal fun probeRuns(
    exe: Path,
    timeout: Duration,
) {
    val out =
        try {
            Command(exe.toString(), listOf("--version")).runBounded(timeout, PROBE_OUTPUT)
        } catch (e: ProcessException) {
            throw e.stoppedPid?.let { probeStopped(exe.toString(), timeout, it.toLong()) } ?: KetchError.Io(exe, e)
        }
    probeFinished(exe, out)
}

/** A clean answer and a started-but-failed run are different reports. */
private fun probeFinished(
    exe: Path,
    out: Output,
) {
    if (out.status.success) return
    throw KetchError.Command("$exe --version", out.status.toString(), out.stderr.decodeToString())
}

/** The probe did not finish. The child was stopped when it had a pid. */
internal fun probeStopped(
    program: String,
    timeout: Duration,
    pid: Long?,
): KetchError =
    KetchError.Msg(
        "${stoppedDetail(program, pid, timeout)}; the previous binary was kept. Antivirus software that inspects new executables " +
            "before allowing them to start can cause this — exclude the ketch directory from it or try again",
    )

/** The one file in an unpacked ketch release that is ketch. */
private fun findBinary(payload: Path): Path =
    walk(payload, onError = {})
        .firstOrNull { it.kind == EntryKind.File && it.path.name == bootstrapBinaryName() }
        ?.path ?: throw KetchError.EmptyPayload(payload)
