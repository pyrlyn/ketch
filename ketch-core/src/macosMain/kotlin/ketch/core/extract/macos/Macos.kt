// macOS container formats.
//
// Both shell out to the system tools, because reimplementing HFS+/APFS image reading or Apple's flat-package format
// would be a large amount of code with no upside — `hdiutil` and `pkgutil` ship on every Mac.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.extract.macos

import fs.EntryKind
import fs.TempDir
import fs.entryKind
import fs.isDir
import fs.listDir
import fs.walk
import ketch.core.error.KetchError
import ketch.core.error.ioAt
import ketch.core.extract.Extractor
import ketch.core.extract.resolvesInside
import ketch.core.text.rustTrim
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.SEEK_END
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read
import process.Command
import process.ExitStatus
import process.ProcessException
import process.runBounded
import kotlin.time.Duration.Companion.minutes

private const val HDIUTIL = "/usr/bin/hdiutil"
private const val PKGUTIL = "/usr/sbin/pkgutil"
private const val DITTO = "/usr/bin/ditto"

/**
 * How long one system tool may run. Rust waits as long as it takes; a bounded run turns a tool stuck on something
 * (a disk image asking for a licence nobody can answer, say) into an error, so the limit is far past any real copy.
 */
private val TOOL_TIMEOUT = 60.minutes

/** What a tool may print before it is cut off; `hdiutil attach` lists a few lines per partition. */
private const val TOOL_OUTPUT = 1L shl 20

/**
 * Run a system tool and return its stdout.
 *
 * stdin is closed so a tool that decides to prompt (a DMG with a licence agreement, say) fails fast instead of hanging
 * the install forever.
 */
fun runTool(
    program: String,
    args: List<String>,
): String {
    val cmd = "$program ${args.joinToString(" ")}"
    val out =
        try {
            Command(program, args).runBounded(TOOL_TIMEOUT, TOOL_OUTPUT)
        } catch (e: ProcessException) {
            throw KetchError.Command(cmd, e.detail, e.stderr.decodeToString().rustTrim(), e)
        }
    if (!out.status.success) {
        val status = (out.status as? ExitStatus.Exited)?.let { "exit ${it.code}" } ?: "killed by signal"
        throw KetchError.Command(cmd, status, out.stderr.decodeToString().rustTrim())
    }
    return out.stdout.decodeToString()
}

/** Same, but a failure is reported rather than fatal. Used for the best-effort cleanup paths where failing would lose the real error. */
fun tryTool(
    program: String,
    args: List<String>,
): Boolean =
    try {
        Command(program, args).runBounded(TOOL_TIMEOUT, TOOL_OUTPUT).status.success
    } catch (_: ProcessException) {
        false
    }

/**
 * `ditto` rather than a hand-rolled copy: it preserves extended attributes, symlinks and resource forks, and a `.app`
 * whose code signature is broken by a naive copy will refuse to launch.
 */
fun copyTree(
    src: Path,
    dest: Path,
) {
    runTool(DITTO, listOf(src.toString(), dest.toString()))
}

private val KOLY = "koly".encodeToByteArray()

/** Where the `koly` trailer that closes every Apple disk image starts, counted from the end. */
private const val TRAILER_LEN = 512L

/** The 512-byte `koly` trailer that closes every Apple disk image; read in place, since images run to gigabytes. */
private fun hasKolyTrailer(path: Path): Boolean {
    val fd = open(path.toString(), O_RDONLY or O_CLOEXEC)
    if (fd < 0) return false
    return try {
        val magic = ByteArray(KOLY.size)
        // A file shorter than the trailer fails the seek, so its first bytes are never mistaken for one.
        lseek(fd, -TRAILER_LEN, SEEK_END) >= 0 &&
            magic.usePinned { read(fd, it.addressOf(0), KOLY.size.convert()) }.toInt() == KOLY.size &&
            magic.contentEquals(KOLY)
    } finally {
        close(fd)
    }
}

/** `.dmg` — attached read-only with no browse and no auto-open, contents copied out, then detached even if the copy failed. */
object DmgExtractor : Extractor {
    override val id = "dmg"

    override fun detect(
        path: Path,
        head: ByteArray,
    ) = hasKolyTrailer(path)

    override fun extract(
        src: Path,
        dest: Path,
    ) = ioAt(dest) { TempDir.create() }.use { mountRoot ->
        val attach = listOf("attach", src.toString(), "-nobrowse", "-noautoopen", "-readonly", "-noverify", "-mountrandom")
        val listing = runTool(HDIUTIL, attach + mountRoot.path.toString())
        val mount = findMountPoint(listing)
        if (mount == null) {
            // The image is attached even though nothing mounted, and the mount point is the handle we would normally
            // detach by. The device node is the only one left; without it the image stays attached for the rest of
            // the session with nothing pointing at it.
            findDevice(listing)?.let { tryTool(HDIUTIL, listOf("detach", it, "-force")) }
            throw KetchError.Msg("hdiutil attached $src but reported no mount point")
        }
        // Detached whether or not the copy worked, or the image stays attached for the rest of the session.
        try {
            copyVolume(mount, dest)
        } finally {
            tryTool(HDIUTIL, listOf("detach", mount.toString(), "-force"))
        }
    }
}

/**
 * Pull the mount point out of `hdiutil attach` output.
 *
 * Lines are tab-separated `device \t type \t mountpoint`, and only some partitions are mounted at all, so the mount
 * point is the last field of the last line that has one.
 */
internal fun findMountPoint(listing: String): Path? =
    listing
        .lines()
        .map { it.split('\t').last().rustTrim() }
        .filter { it.startsWith('/') }
        .map(::Path)
        .lastOrNull { isDir(it) }

/**
 * The device node `hdiutil attach` created, from the first line that names one.
 *
 * Detaching by device takes the whole image down, partitions included, which is exactly what is wanted when none of
 * them mounted.
 */
internal fun findDevice(listing: String): String? =
    listing
        .lines()
        .map { it.split('\t').first().rustTrim() }
        .firstOrNull { it.startsWith("/dev/") }

internal fun copyVolume(
    mount: Path,
    dest: Path,
) {
    // Volume bookkeeping and the customary `/Applications` drop-target.
    val names =
        ioAt(mount) { listDir(mount) }.filter { !it.startsWith('.') && entryKind(Path(mount, it)) != EntryKind.Symlink }
    for (name in names) copyTree(Path(mount, name), Path(dest, name))
    if (names.isEmpty()) throw KetchError.EmptyPayload(mount)
    ensureLinksInside(dest)
}

/**
 * Refuse a copied payload holding any link that leads out of it.
 *
 * `ditto` copies links exactly as the image or package holds them, so only the top level of a volume was ever
 * filtered: a nested `Contents/x -> /etc` or `-> ../../..` arrived intact, and whatever reads the payload next (binary
 * discovery, man pages, the changelog) would follow it out.
 */
internal fun ensureLinksInside(dest: Path) {
    val walked = walk(dest, onError = { throw KetchError.Msg("walking $dest: ${it.message}", it) })
    for (entry in walked) {
        if (entry.kind == EntryKind.Symlink && !resolvesInside(entry.path, dest)) {
            throw KetchError.Msg("refusing payload link ${entry.path} that resolves outside the payload")
        }
    }
}

/** `.pkg` / `.mpkg` — expanded and unpacked into the store rather than installed system-wide, so ketch stays the only thing that owns the files and `ketch uninstall` can actually undo it. */
object PkgExtractor : Extractor {
    override val id = "pkg"

    private val XAR = "xar!".encodeToByteArray()

    override fun detect(
        path: Path,
        head: ByteArray,
    ) = head.size >= XAR.size && XAR.indices.all { head[it] == XAR[it] }

    override fun extract(
        src: Path,
        dest: Path,
    ) = ioAt(dest) { TempDir.create() }.use { work ->
        // `pkgutil` insists the destination not exist yet.
        val expanded = Path(work.path, "expanded")
        runTool(PKGUTIL, listOf("--expand-full", src.toString(), expanded.toString()))
        val payloads = findPayloadRoots(expanded)
        if (payloads.isEmpty()) throw KetchError.Msg("$src expanded but contained no payload")
        for (payload in payloads) {
            for (name in ioAt(payload) { listDir(payload) }) copyTree(Path(payload, name), Path(dest, name))
        }
        ensureLinksInside(dest)
    }
}

/** How deep under the expansion a payload directory may sit. */
private const val PAYLOAD_DEPTH = 4

/**
 * Find the directories holding the actual files.
 *
 * `pkgutil --expand-full` writes one `<component>.pkg/Payload/` per component; older layouts call it `Root`. Everything
 * else in the expansion is install metadata that has no meaning outside the system installer.
 */
private fun findPayloadRoots(expanded: Path): List<Path> =
    // walkdir's `filter_map(Result::ok)`: an unreadable corner of the expansion holds no payload.
    walk(expanded, PAYLOAD_DEPTH, onError = {})
        .filter { it.kind == EntryKind.Dir && (it.path.name == "Payload" || it.path.name == "Root") }
        .map { it.path }
        .toList()
