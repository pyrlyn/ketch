package ketch.core.extract

import fs.EntryKind
import fs.FsException
import fs.Links
import fs.canon
import fs.canonicalize
import fs.copyFile
import fs.copyTree
import fs.createDir
import fs.entryKind
import fs.hardLink
import fs.isDir
import fs.joinPath
import fs.removeFile
import fs.setMode
import ketch.core.config.HOST_WINDOWS
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.io
import ketch.core.error.ioAt
import ketch.core.model.rustComponents
import ketch.core.platform.pathIsWithin
import ketch.core.platform.symlink
import kotlinx.io.files.Path

private val PERMISSION_BITS = "777".toInt(8)
private val OWNER_ALL = "700".toInt(8)

/** Permission bits taken from an untrusted archive: never setuid, setgid or sticky. */
internal fun payloadMode(mode: Int) = mode and PERMISSION_BITS

internal fun setUnixMode(
    path: Path,
    mode: Int,
) = ioAt(path) { setMode(path, mode) }

/** [rel], a `/`-separated path, under [base]; an absolute [rel] stands alone (Rust `Path::join`). */
internal fun join(
    base: Path,
    rel: String,
) = if (rel.startsWith('/')) Path(rel) else joinPath(base, rel)

private fun isFile(path: Path) = entryKind(canon(path)) == EntryKind.File

/**
 * A link inside the payload may only point at something else inside it.
 *
 * Checked lexically, before the link exists: a symlink to `../../../.ssh` would otherwise turn the next `place` into
 * an arbitrary-file copy. [member] is a [safeMemberPath] result.
 */
internal fun checkLinkTarget(
    member: String,
    target: String,
) {
    val reject = KetchError.Msg("refusing archive link $member -> $target that escapes the target directory")
    if (target.startsWith('/')) throw reject
    // Depth of the directory holding the link, relative to the payload root.
    var depth = member.split('/').size - 1
    for (part in target.split('/').filter { it.isNotEmpty() && it != "." }) {
        depth += if (part == "..") -1 else 1
        if (depth < 0) throw reject
    }
}

/**
 * Walk every component of [rel] below [dest], refusing any that is a symlink.
 *
 * This is what makes [checkLinkTarget] sound. That guard counts depth from the member *name*, but creating
 * directories and every write follow links, so a member called `a/link/b` lands wherever `link` points once an earlier
 * member of the same archive planted it — and the surplus `..` in a later target then escapes [dest]. Refusing to
 * traverse any link below [dest] keeps the path we counted and the path the kernel resolves the same one, for files,
 * directories, symlinks and hard links alike.
 *
 * [dest] itself is ketch's own directory and is trusted: on macOS it usually *is* reached through a symlink (`/tmp`
 * -> `private/tmp`).
 */
internal fun walkInside(
    dest: Path,
    rel: String,
    create: Boolean,
) {
    var built = dest
    for (part in rel.split('/').filter { it.isNotEmpty() }) {
        built = joinPath(built, part)
        when (entryKind(built)) {
            EntryKind.Symlink -> throw KetchError.Msg("refusing archive entry that resolves through $built")

            // Nothing exists from here down, so nothing below can be a link.
            null -> if (create) ioAt(built) { createDir(built) } else return

            // An ordinary directory, or a file at the leaf: both are fine.
            else -> Unit
        }
    }
}

/**
 * Make `dest/rel` safe to write: its directories exist, none of them is a link, and it is not itself a link an
 * earlier member planted for us to write through (creating the file would happily truncate the far end).
 */
internal fun ensureParent(
    dest: Path,
    rel: String,
) {
    walkInside(dest, rel.substringBeforeLast('/', ""), create = true)
    val out = joinPath(dest, rel)
    if (entryKind(out) == EntryKind.Symlink) ioAt(out) { removeFile(out) }
}

/** Set directory modes after every member is on disk, deepest first, always leaving the owner `rwx`: ketch has to be able to remove its own store on uninstall and upgrade, whatever the archive asked for. */
internal fun applyDirModes(dirs: List<Pair<Path, Int>>) {
    for ((path, mode) in dirs.sortedByDescending { rustComponents(it.first).size }) setUnixMode(path, payloadMode(mode) or OWNER_ALL)
}

/** Create hard links, then symlinks, after the rest of the archive is on disk; each pair is where the link goes and what it names. */
internal fun finishDeferredLinks(
    dest: Path,
    hardlinks: List<Pair<Path, String>>,
    symlinks: List<Pair<Path, String>>,
) {
    for ((out, safeTarget) in hardlinks) {
        // The kernel resolves the source too, so a hard link to `planted-link/.ssh/id_rsa` would pull a file from
        // outside the payload into it.
        walkInside(dest, safeTarget, create = false)
        writeHardlink(joinPath(dest, safeTarget), out)
    }
    for ((out, target) in symlinks) writeSymlink(target, out)
    // Only now, with every link in place, does a target mean what the kernel will make of it: a later link can
    // reroute an earlier one.
    for ((out, target) in symlinks) {
        if (entryKind(out) == EntryKind.Symlink && !resolvesInside(out, dest)) {
            throw KetchError.Msg("refusing archive link $out -> $target that resolves outside the target directory")
        }
    }
}

/** Prefer a real hard link; fall back to a same-tree copy when the filesystem refuses (cross-volume extracts, some Windows setups) — the same policy as [materializeSymlinkAsCopy] for symlinks. */
internal fun writeHardlink(
    source: Path,
    out: Path,
) {
    try {
        hardLink(source, out)
    } catch (e: FsException) {
        if (!isFile(source)) throw KetchError.io(out, e)
        ioAt(out) { copyFile(source, out) }
    }
}

/**
 * Windows prefers a real symlink when the process may create one (Developer Mode or SeCreateSymbolicLink), and falls
 * back to a same-tree copy so a release that ships `bin/tool -> tool.exe` still places a runnable file.
 */
internal fun writeSymlink(
    target: String,
    link: Path,
) {
    if (!HOST_WINDOWS) return symlink(Path(target), link)
    if (runCatching { symlink(Path(target), link) }.isFailure) materializeSymlinkAsCopy(target, link)
}

/**
 * Copy the symlink target into [link] when it already exists under the payload.
 *
 * Used where creating a real symlink is refused: silently succeeding without writing left packages missing their
 * linked binaries. Directories are copied too — releases ship `share/doc -> ../docs` and without this branch Windows
 * extract failed with "not a file".
 */
internal fun materializeSymlinkAsCopy(
    target: String,
    link: Path,
) {
    val parent = link.parent ?: Path(".")
    val resolved = join(parent, target)
    when {
        isFile(resolved) -> {
            ioAt(link) { copyFile(resolved, link) }
        }

        isDir(resolved) -> {
            // `d -> .` or `sub/d -> ..` passes the lexical target check, yet copying it would copy the link's own
            // directory into itself.
            val source = ioAt(resolved) { canonicalize(resolved) }
            val home = ioAt(parent) { canonicalize(parent) }
            if (pathIsWithin(home, source)) {
                throw KetchError.Msg("cannot materialise archive symlink $link → $target: the target is an ancestor of the link")
            }
            copyDirContents(resolved, link)
        }

        else -> {
            throw KetchError.Msg(
                "cannot materialise archive symlink $link → $target (target missing; on Windows enable Developer Mode " +
                    "or ship a real file/dir)",
            )
        }
    }
}

/**
 * Copy [src] into [dest] for directory symlink materialisation. Nested links are left out: creating them would
 * re-enter the Windows privilege problem this path exists to avoid, and following them could escape the payload root.
 */
private fun copyDirContents(
    src: Path,
    dest: Path,
) {
    try {
        copyTree(src, dest, Links.Skip)
    } catch (e: FsException) {
        throw KetchError.fsKit(src, e)
    }
}
