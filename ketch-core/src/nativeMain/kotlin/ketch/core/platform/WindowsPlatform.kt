// Windows.
//
// Assets are `.exe` and `.zip`. Placement copies into the bin dir rather than linking: creating a symlink needs a
// privilege most users do not have. Every copy is recorded so uninstall can prove identity before deleting.
package ketch.core.platform

import fs.EntryKind
import fs.FsException
import fs.canon
import fs.copyFile
import fs.createDirAll
import fs.entryKind
import fs.listDir
import fs.readLink
import fs.removeAny
import fs.removeFile
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.ioAt
import ketch.core.extra.ExtraPlacement
import ketch.core.extra.resolveUnder
import ketch.core.extract.Extractor
import ketch.core.extract.isProgramHead
import ketch.core.extract.portableExtractors
import ketch.core.extract.readHead
import ketch.core.model.CompletionShell
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.model.rustComponents
import ketch.core.report.Report
import ketch.core.selfupdate.isTransientLock
import ketch.core.text.asciiLowercase
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.time.Clock

/**
 * Native Windows CLI releases: copy executables, record every destination.
 *
 * Built for every native target, as [LinuxPlatform] is, so its placement is tested on the Mac too; [host] picks it on
 * Windows.
 */
// One method per thing that differs between operating systems, as the interface has them.
@Suppress("TooManyFunctions")
class WindowsPlatform(
    private val target: TargetSpec = TargetSpec.host(),
) : Platform {
    override val id = "windows"

    override fun target() = target

    override fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ) = scoreWindowsAsset(assetName, target.arch)

    override fun extractors(): List<Extractor> = portableExtractors()

    override fun binCandidates(
        payload: Path,
        kind: PackageKind,
        pkg: String,
    ): List<Path> = if (kind == PackageKind.App) emptyList() else discoverWindowsExecutables(payload, pkg)

    // Install, upgrade, relink and rollback all come through here.
    override fun place(plan: Placement): List<LinkRecord> {
        sweepBusyAsides(plan.replacing)
        if (plan.link) preflight(plan)
        val swapped = swapIntoStore(plan.payloadDir, plan.storeDir, ::copyLocalTree)
        if (!plan.link) {
            if (swapped) finalizeStoreSwap(plan.storeDir)
            return emptyList()
        }
        val replacing = if (swapped) retiredTargets(plan.replacing, plan.storeDir) else plan.replacing
        val links = mutableListOf<LinkRecord>()
        // Named like [clearForReplace]'s asides: one a running exe keeps undeletable is then the same kind of leftover,
        // swept the same way.
        val claims = Claims(::busyAside)
        var placed = false
        try {
            for ((target, name) in cliTargets(plan.storeDir, plan)) links += copyBinary(target, plan.binDir, name, replacing, claims)
            links += linkPlannedWindowsExtras(plan.extras, plan.storeDir, replacing, claims)
            if (links.isEmpty()) throw KetchError.EmptyPayload(plan.storeDir)
            placed = true
        } finally {
            if (placed) {
                claims.discard()
                if (swapped) finalizeStoreSwap(plan.storeDir)
            } else {
                runCatching { unplace(links, Report.silent()) }
                claims.restore()
                if (swapped) restoreRetiredStore(plan.storeDir)
            }
        }
        return links
    }

    /** Every destination is checked before anything moves. */
    private fun preflight(plan: Placement) {
        val destinations = mutableSetOf<String>()

        fun claim(dest: Path) {
            if (!destinations.add(destKey(dest))) throw KetchError.Msg("multiple payload entries want to create $dest")
            destinationAvailable(dest, plan.replacing)
        }
        for ((_, name) in cliTargets(plan.payloadDir, plan)) claim(Path(plan.binDir, name))
        for (extra in plan.extras) {
            resolveUnder(plan.payloadDir, extra.relPath)
            claim(extra.dest)
        }
        if (destinations.isEmpty()) throw KetchError.EmptyPayload(plan.payloadDir)
    }

    private fun cliTargets(
        root: Path,
        plan: Placement,
    ): List<Pair<Path, String>> =
        when {
            plan.kind == PackageKind.App -> {
                emptyList()
            }

            plan.binSpecs.isNotEmpty() -> {
                resolveBinSpecs(root, plan.binSpecs, ::namesBinary, ::windowsBinName)
            }

            else -> {
                discoverWindowsExecutables(root, plan.name).let { found ->
                    found.map { path ->
                        val stem = if (found.size == 1 && looksLikeBuildArtifact(path.name)) plan.name else path.name
                        path to windowsBinName(stem)
                    }
                }
            }
        }

    private fun discoverWindowsExecutables(
        root: Path,
        pkg: String,
    ) = discoverExecutables(this, root, pkg, bundles = false)

    override fun completionDir(shell: CompletionShell): Path =
        if (shell == CompletionShell.Powershell) {
            Path(Path(documentsDir() ?: dataHome(), "PowerShell"), "Completions")
        } else {
            completionDirFor(shell)
        }

    override fun unplace(
        links: List<LinkRecord>,
        report: Report,
    ) {
        sweepBusyAsides(links)
        for (record in links.filter { entryKind(it.link) != null }) {
            if (!stillCopied(record)) {
                report.debug("leaving ${record.link}: it is no longer what ketch placed there")
                continue
            }
            try {
                removeAny(record.link)
            } catch (e: FsException) {
                throw KetchError.fsKit(record.link, e)
            }
        }
    }

    override fun isExecutable(path: Path): Boolean {
        if (entryKind(canon(path)) != EntryKind.File) return false
        val name = path.name.asciiLowercase()
        return PROGRAM_SUFFIXES.any { name.endsWith(it) } || runCatching { isProgramHead(readHead(path)) }.getOrDefault(false)
    }

    override fun doctor(cfg: Config): List<DoctorCheck> =
        listOf("root" to cfg.root, "store" to cfg.storeDir, "bin" to cfg.binDir).map { (label, dir) ->
            val why = if (entryKind(dir) == null) "does not exist" else probeWritable(dir)
            if (why == null) DoctorCheck.ok(label, "$dir is writable") else DoctorCheck.fail(label, "$dir: $why", "mkdir $dir")
        }
}

/** What Windows runs from PATH without being told how. */
private val PROGRAM_SUFFIXES = listOf(".exe", ".cmd", ".bat", ".com", ".ps1")

/** The Documents folder, where PowerShell looks for completions; null off Windows or when the shell cannot say. */
internal expect fun documentsDir(): Path?

/** The per-user local application data folder (Rust: `dirs::data_local_dir`); null off Windows or when the shell cannot say. */
internal expect fun localDataDir(): Path?

/** Keep a Windows executable suffix, or add `.exe` so PATH lookup finds it. */
internal fun windowsBinName(name: String): String = if (PROGRAM_SUFFIXES.any { name.asciiLowercase().endsWith(it) }) name else "$name.exe"

/** `bin = [{ name = "rtok" }]` must find `rtok.exe`: the name in any case, or with a suffix cmd runs. */
private fun namesBinary(
    file: String,
    want: String,
) = file == want || file.eqIgnoreAsciiCase(want) || listOf(".exe", ".cmd", ".bat").any { file.eqIgnoreAsciiCase(want + it) }

/**
 * A destination as Windows compares it: ASCII case folded, and either slash, since a path ketch joins and one Rust
 * ketch recorded spell the separator differently.
 */
internal fun destKey(path: Path): String = path.toString().replace('/', '\\').asciiLowercase()

/**
 * Windows `ERROR_ACCESS_DENIED` (5) or `ERROR_SHARING_VIOLATION` (32), plus `PermissionDenied`. A running `.exe`
 * typically cannot be deleted or overwritten, but it *can* be renamed aside so a fresh copy can take its path.
 */
internal fun isBusy(e: FsException) = isTransientLock(e)

/** `<name>.old.<pid>-<millis>` beside [path], the shape [isBusyAsideOf] recognises. */
internal fun busyAside(path: Path): Path = sibling(path, ".old.${getpid()}-${Clock.System.now().toEpochMilliseconds().coerceAtLeast(0)}")

/**
 * Free [path] so a replacement can be written there.
 *
 * Prefer delete. If the file is mapped by a live process (the usual Windows `Access is denied` on `ketch self upgrade`
 * or `ketch upgrade`), rename it aside instead — the running image keeps its handle, and the name becomes free.
 */
internal fun clearForReplace(path: Path) {
    try {
        removeAny(path)
    } catch (e: FsException) {
        if (!isBusy(e)) throw e
        val aside = busyAside(path)
        runCatching { removeAny(aside) }
        rename(path, aside)
        // Best effort: often still fails while the image is mapped.
        runCatching { removeFile(aside) }
    }
}

/**
 * Remove the asides [clearForReplace] left beside [links] once the executable they held has stopped.
 *
 * A running tool upgraded in place leaves `tool.exe.old.<pid>-<ms>` behind, and nothing else will ever remove it:
 * without this each upgrade of a tool that was running adds one more. Best effort — one still running stays for the
 * next sweep.
 */
internal fun sweepBusyAsides(links: List<LinkRecord>) {
    for (record in links) {
        val dir = record.link.parent
        val name = record.link.name
        val entries = dir?.takeIf { name.isNotEmpty() }?.let { runCatching { listDir(it) }.getOrNull() } ?: continue
        for (entry in entries.filter { isBusyAsideOf(name, it) }) {
            val aside = Path(dir, entry)
            if (entryKind(aside) == EntryKind.File) runCatching { removeFile(aside) }
        }
    }
}

private fun copyOver(
    from: Path,
    to: Path,
) {
    try {
        copyFile(from, to)
    } catch (e: FsException) {
        if (!isBusy(e)) throw e
        clearForReplace(to)
        copyFile(from, to)
    }
}

/**
 * [recorded] with targets under [store] pointed at its retired copy.
 *
 * A copy is ours only while its bytes match its target. Reinstalling into the same store path — a new build under a
 * dev package's floating tag — has just moved the bytes those copies were made from to `.old`, so comparing against
 * the new store would call every one of our own copies someone else's.
 */
private fun retiredTargets(
    recorded: List<LinkRecord>,
    store: Path,
): List<LinkRecord> {
    val retired = sibling(store, ".old")
    if (entryKind(retired) == null) return recorded
    val storeParts = rustComponents(store)
    return recorded.map { record ->
        val parts = rustComponents(record.target)
        if (parts.take(storeParts.size) != storeParts) {
            record
        } else {
            record.copy(target = parts.drop(storeParts.size).fold(retired) { dir, part -> Path(dir, part) })
        }
    }
}

private const val CHUNK = 8192

/** Whether two files hold the same bytes; false when either cannot be read. */
internal fun sameContents(
    a: Path,
    b: Path,
): Boolean =
    runCatching {
        SystemFileSystem.source(a).buffered().use { fa -> SystemFileSystem.source(b).buffered().use { fb -> sameStream(fa, fb) } }
    }.getOrDefault(false)

private fun sameStream(
    a: Source,
    b: Source,
): Boolean {
    val ba = ByteArray(CHUNK)
    val bb = ByteArray(CHUNK)
    while (true) {
        val na = readFull(a, ba)
        val nb = readFull(b, bb)
        if (na != nb || !ba.copyOf(na).contentEquals(bb.copyOf(nb))) return false
        if (na == 0) return true
    }
}

private fun readFull(
    source: Source,
    buf: ByteArray,
): Int {
    var n = 0
    while (n < buf.size) {
        val got = source.readAtMostTo(buf, n, buf.size)
        if (got <= 0) break
        n += got
    }
    return n
}

/** Whether what is at a recorded path is still what ketch put there; a copy proves it by its bytes. */
private fun stillCopied(record: LinkRecord): Boolean =
    when (record.kind) {
        LinkKind.CopiedFile -> entryKind(record.link) == EntryKind.File && sameContents(record.link, record.target)
        LinkKind.Symlink, LinkKind.LinkedApp -> readLink(record.link)?.let(::rustComponents) == rustComponents(record.target)
        LinkKind.CopiedApp -> entryKind(record.link) == EntryKind.Dir && entryKind(canon(record.target)) != null
    }

private fun isOurs(
    link: Path,
    recorded: List<LinkRecord>,
) = recorded.any { destKey(it.link) == destKey(link) && stillCopied(it) }

private fun destinationAvailable(
    link: Path,
    recorded: List<LinkRecord>,
) {
    if (entryKind(link) != null && !isOurs(link, recorded)) {
        throw KetchError.Msg("$link already exists and was not installed by ketch for this package; move it aside first")
    }
}

/**
 * Clear a destination, or explain who already has it. What was there moves aside into [claims] — a rename, which also
 * frees the name of a running `.exe` — so a placement that fails later can put it back.
 */
private fun clearDestination(
    link: Path,
    recorded: List<LinkRecord>,
    claims: Claims,
) {
    destinationAvailable(link, recorded)
    claims.take(link)
}

internal fun copyBinary(
    target: Path,
    binDir: Path,
    name: String,
    recorded: List<LinkRecord>,
    claims: Claims,
): LinkRecord {
    ioAt(binDir) { createDirAll(binDir) }
    val link = Path(binDir, name)
    // Case-insensitive: Tool.exe and tool.exe are the same destination.
    runCatching { listDir(binDir) }.getOrNull()?.firstOrNull { destKey(Path(binDir, it)) == destKey(link) }?.let {
        clearDestination(Path(binDir, it), recorded, claims)
    }
    clearDestination(link, recorded, claims)
    ioAt(link) { copyOver(target, link) }
    return LinkRecord(link, target, LinkKind.CopiedFile, LinkRole.Binary)
}

/** Copy classified extras from [root] into their user destinations. */
internal fun linkPlannedWindowsExtras(
    extras: List<ExtraPlacement>,
    root: Path,
    recorded: List<LinkRecord>,
    claims: Claims,
): List<LinkRecord> =
    extras.map { extra ->
        val target = resolveUnder(root, extra.relPath)
        extra.dest.parent?.let { parent -> ioAt(parent) { createDirAll(parent) } }
        clearDestination(extra.dest, recorded, claims)
        ioAt(extra.dest) { copyFile(target, extra.dest) }
        LinkRecord(extra.dest, target, LinkKind.CopiedFile, extra.role)
    }
