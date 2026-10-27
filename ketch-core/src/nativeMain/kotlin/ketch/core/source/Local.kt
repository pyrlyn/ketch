// Packages that already live on the local filesystem. A `local:<path>` reference points at an archive, a bare binary,
// a symlink to either, or a macOS `.app` bundle. There is no remote release stream: the source synthesises a single
// release so the rest of the install pipeline can stay source-agnostic. Paths are resolved to absolute form when
// recorded so `ketch list` and `ketch info` keep working after the working directory moves.
package ketch.core.source

import fs.EntryKind
import fs.FsException
import fs.Links
import fs.canonicalize
import fs.copyFile
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.linkMode
import fs.readLink
import fs.walk
import ketch.core.cancel.Cancel
import ketch.core.config.currentDir
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.ioAt
import ketch.core.extract.isBundleName
import ketch.core.extract.isContainerHead
import ketch.core.extract.looksLikeTar
import ketch.core.extract.readHead
import ketch.core.hash.Sha256
import ketch.core.http.sha256File
import ketch.core.model.LocalKind
import ketch.core.model.PATH_ORDER
import ketch.core.model.PackageRef
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.SourceInfo
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import ketch.core.report.ProgressSink
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Tag of the synthetic release every local install records. */
const val LOCAL_TAG = "local"

/** Version of the synthetic release every local install records. */
const val LOCAL_VERSION = "0.0.0-local"

private const val PERMISSION_BITS = 0b111_111_111

/** The built-in `local` source. */
class LocalSource : Source {
    override fun scheme() = "local"

    override fun describe(id: String): SourceInfo {
        val path = resolvePath(id)
        val kind = classify(path)
        return SourceInfo(
            id = path.toString(),
            name = path.name.ifEmpty { path.toString() },
            description = "local ${kind.asStr} at $path",
            homepage = fileUrl(path),
        )
    }

    override fun listReleases(
        id: String,
        opts: ListOpts,
    ) = listOf(syntheticRelease(id))

    // One synthetic release: exact requests for anything else fail the same way a missing GitHub tag would. There is
    // never a prerelease listing to widen, so `opts` is unused on purpose.
    override fun resolve(
        id: String,
        want: VersionSpec,
        opts: ListOpts,
    ): Release {
        val release = syntheticRelease(id)
        val tag = (want as? VersionSpec.Exact)?.version ?: return release
        if (tag.eqIgnoreAsciiCase(LOCAL_TAG) || tag.eqIgnoreAsciiCase(LOCAL_VERSION) || release.version.matchesRequest(tag)) return release
        throw KetchError.NoRelease("$id@$tag")
    }

    override fun download(
        asset: ReleaseAsset,
        dest: Path,
        progress: ProgressSink,
        cancel: Cancel,
    ): String {
        cancel.check()
        val src = Path(asset.url)
        if (!SystemFileSystem.exists(src)) throw KetchError.Msg("local path does not exist: $src")
        // Directories are handled in `install.prepare` (`.app` bundles). A bare directory reaching download is a
        // programming error upstream.
        if (isDir(src)) throw KetchError.Msg("local path is a directory, not an installable file: $src")
        ensureLocalPayload(src)
        val size = SystemFileSystem.metadataOrNull(src)?.size ?: 0
        progress.start(size, asset.name)
        dest.parent?.let { parent -> ioAt(parent) { createDirAll(parent) } }
        // The copy follows symlinks for content, which is what we want: the payload is the target's bytes; the kind
        // recorded on the package is what remembered that the origin was a link.
        ioAt(dest) { copyFile(src, dest) }
        progress.advance(size)
        progress.finish("copied")
        return sha256File(dest)
    }

    override fun webUrl(id: String): String? = runCatching { resolvePath(id) }.getOrNull()?.let(::fileUrl)
}

private fun syntheticRelease(id: String): Release {
    val path = resolvePath(id)
    val kind = classify(path)
    // `.app` still needs a release so resolve and info work; download itself refuses directories and `prepare` copies
    // the bundle instead.
    val bundle = kind == LocalKind.App || isDir(path)
    // For a symlink, report the link's name but the size of the target when we can.
    val size = if (bundle) 0 else SystemFileSystem.metadataOrNull(path)?.size ?: 0
    val name = path.name.ifEmpty { if (bundle) "app" else "payload" }
    return Release(
        version = Version.parse(LOCAL_VERSION),
        tag = LOCAL_TAG,
        notes = "local ${kind.asStr} at $path",
        assets = listOf(ReleaseAsset(name, path.toString(), size)),
    )
}

/**
 * Turn a `local:` id into an absolute path, without requiring it to exist yet when only normalising a user argument.
 * Prefer [resolvePath] at use sites that need the file to be there.
 */
fun absolutePath(id: String): Path {
    val raw = id.trim()
    if (raw.isEmpty()) throw KetchError.Msg("local path is empty")
    if (raw.startsWith('/')) return Path(raw)
    val cwd = currentDir() ?: throw KetchError.fsKit(Path("."), FsException.ofErrno(Path(".")))
    return Path(cwd, raw)
}

/** Absolute path that must exist (as a file, symlink, or `.app` directory). */
fun resolvePath(id: String): Path {
    val path = absolutePath(id)
    val kind = entryKind(path)
    if (kind == null) {
        val err = FsException.ofErrno(path)
        throw if (err.kind == FsException.Kind.NotFound) KetchError.Msg("local path does not exist: $path") else KetchError.fsKit(path, err)
    }
    // `exists` follows the link; a dangling symlink still has metadata, and following it at download time would only
    // produce a worse error.
    if (kind == EntryKind.Symlink && !SystemFileSystem.exists(path)) throw KetchError.Msg("local symlink is dangling: $path")
    return path
}

/** A `PackageRef` whose id is the absolute form of [path]. */
fun packageRef(path: Path) = PackageRef("local", path.toString())

/** What a link points at, following every hop; an unreadable target is an error at [path]. */
private fun targetKind(path: Path): EntryKind? = ioAt(path) { entryKind(canonicalize(path)) }

/** Classify what a local path is, for display and for the install branch. */
fun classify(path: Path): LocalKind =
    when (lstatKind(path)) {
        EntryKind.Dir -> {
            if (!isBundleName(path.name)) {
                throw KetchError.Msg("refusing to install local directory `$path`; only a file, archive, or `.app` bundle is supported")
            }
            LocalKind.App
        }

        EntryKind.Symlink -> {
            val target = targetKind(path)
            if (target != EntryKind.Dir) ensureIsRegularFile(path, target)
            LocalKind.Symlink
        }

        else -> {
            ensureIsRegularFile(path, lstatKind(path))
            if (looksLikeArchive(readHead(path))) LocalKind.Archive else LocalKind.Binary
        }
    }

private fun lstatKind(path: Path): EntryKind = entryKind(path) ?: throw KetchError.fsKit(path, FsException.ofErrno(path))

/**
 * The same bytes the extractors sniff, without opening the file to see whether a compressed stream wraps tar. A lone
 * `.gz` is still an archive here; the raw-binary extractor is not.
 */
internal fun looksLikeArchive(head: ByteArray) = isContainerHead(head) || looksLikeTar(head)

private fun fileUrl(path: Path) = "file://$path"

/**
 * Refuse FIFOs, sockets and devices before `open` or `copy` can block on them. Directories are left to [classify] and
 * `prepare`; a symlink is followed only far enough to see whether the payload is a regular file.
 */
private fun ensureLocalPayload(path: Path) {
    val kind = lstatKind(path)
    val payload = if (kind == EntryKind.Symlink) targetKind(path) else kind
    if (payload != EntryKind.Dir) ensureIsRegularFile(path, payload)
}

private fun ensureIsRegularFile(
    path: Path,
    kind: EntryKind?,
) {
    if (kind != EntryKind.File) throw KetchError.Msg("local path is not a regular file: $path")
}

/**
 * Copy a symlink's target into [link] when the host cannot recreate links: used on Windows so `place` can stage a
 * payload that still contains real symlinks after archive extract. Mirrors the extractors' materialise-as-copy policy.
 */
internal fun materializeLocalSymlink(
    symlink: Path,
    link: Path,
) {
    val target = readLink(symlink) ?: throw KetchError.fsKit(symlink, FsException.ofErrno(symlink))
    // Rust `Path::join`: an absolute target stands alone.
    val resolved = if (target.isAbsolute) target else Path(symlink.parent ?: Path("."), target.toString())
    // Last resort after the target itself: copying the link follows it for file content.
    val file = listOf(resolved, symlink).firstOrNull { isFile(it) && (it == resolved || !isDir(resolved)) }
    when {
        file != null -> {
            link.parent?.let { parent -> ioAt(parent) { createDirAll(parent) } }
            ioAt(link) { copyFile(file, link) }
        }

        isDir(resolved) -> {
            copyTree(resolved, link)
        }

        else -> {
            throw KetchError.Msg("cannot materialise local symlink $symlink → $target (target missing)")
        }
    }
}

/** Rust `Path::is_file`: a regular file, following links. */
private fun isFile(path: Path) = entryKind(fs.canon(path)) == EntryKind.File

/** Copy a directory tree into [dest], preserving relative structure and recreating links as they are. Used for local `.app` bundles. */
fun copyTree(
    src: Path,
    dest: Path,
) = ioAt(dest) { fs.copyTree(src, dest, Links.Keep) }

/**
 * Stable digest of a directory's contents, for the sha256 field of a local `.app`.
 *
 * Each file and each symlink is one framed record: kind, path, permission bits, and either the file bytes or the
 * symlink's own target. Lengths sit in the frame so `a` = `X` plus `b` = `Y` cannot hash like a single `a` whose bytes
 * are `Xb\0Y`. The mode is in the record so dropping `+x` changes the digest, and a symlink is not skipped: retargeting
 * one inside a bundle is a different tree. Paths are taken in Rust's order, component by component, so a lock written
 * by either ketch matches the other.
 */
fun sha256Tree(root: Path): String {
    val hasher = Sha256()
    // A fifo or device is not part of a bundle, and reading it can block.
    val paths =
        ioAt(root) {
            walk(root).filter { it.kind == EntryKind.File || it.kind == EntryKind.Symlink }.map { it.path }.toList()
        }.sortedWith(PATH_ORDER)
    val prefix = root.toString().trimEnd('/') + "/"
    for (path in paths) {
        val rel = path.toString().removePrefix(prefix)
        val mode = linkMode(path) ?: throw KetchError.fsKit(path, FsException.ofErrno(path))
        val link = entryKind(path) == EntryKind.Symlink
        val payload =
            if (link) {
                (readLink(path) ?: throw KetchError.fsKit(path, FsException.ofErrno(path))).toString().encodeToByteArray()
            } else {
                ioAt(path) { SystemFileSystem.source(path).buffered().use { it.readByteArray() } }
            }
        hashRecord(hasher, if (link) 'l' else 'f', rel.encodeToByteArray(), mode and PERMISSION_BITS, payload)
    }
    return hasher.hex()
}

/**
 * `kind || len(path) || path || mode || len(payload) || payload`. Fixed-width big-endian lengths keep a path or a
 * payload from running into the next field. `mode` is the permission bits only: owner and timestamps are not part of
 * what a lockfile is pinning.
 */
private fun hashRecord(
    hasher: Sha256,
    kind: Char,
    path: ByteArray,
    mode: Int,
    payload: ByteArray,
) {
    hasher.update(byteArrayOf(kind.code.toByte()))
    hasher.update(bigEndian(path.size.toLong(), Long.SIZE_BYTES))
    hasher.update(path)
    hasher.update(bigEndian(mode.toLong(), Int.SIZE_BYTES))
    hasher.update(bigEndian(payload.size.toLong(), Long.SIZE_BYTES))
    hasher.update(payload)
}

private fun bigEndian(
    value: Long,
    width: Int,
) = ByteArray(width) { i -> (value ushr (Byte.SIZE_BITS * (width - 1 - i))).toByte() }
