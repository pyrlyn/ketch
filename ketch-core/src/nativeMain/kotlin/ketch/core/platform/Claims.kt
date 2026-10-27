// The destinations one placement took over, and how to give them back.
//
// Placement writes links, copies and app bundles over what the version being replaced put there. Deleting each
// occupant just before writing means a failure halfway — the second link, a full disk while copying a large `.app` —
// leaves the old version's links and app copy gone while state still records them. So an occupant is moved aside, not
// deleted, and only thrown away once the whole placement has succeeded. Kept apart from the backends because every one
// of them needs the same rule.
package ketch.core.platform

import fs.FsException
import fs.entryKind
import fs.removeAny
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.ioAt
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Every destination a placement has claimed, with where its previous occupant was moved to, if it had one. */
internal class Claims(
    /** Names the sibling an occupant moves to; it must be in the same folder, so the move is a rename and cannot fail half done. */
    private val aside: (Path) -> Path = ::asideName,
) {
    private val taken = mutableListOf<Pair<Path, Path?>>()

    /** Clear [path] for a new entry, keeping what was there to put back. The caller has already decided the occupant is ours to replace. */
    fun take(path: Path) {
        if (entryKind(path) == null) {
            taken += path to null
            return
        }
        val aside = aside(path)
        try {
            removeAny(aside)
        } catch (e: FsException) {
            throw KetchError.fsKit(aside, e)
        }
        ioAt(path) { rename(path, aside) }
        taken += path to aside
    }

    /** The placement succeeded: the previous occupants go for good. Best effort — an aside left behind costs space, not correctness. */
    fun discard() {
        for (aside in taken.mapNotNull { it.second }) runCatching { removeAny(aside) }
    }

    /** The placement failed: remove whatever it wrote at each claimed path — a link, or a copy cut short — and move the previous occupant back. */
    fun restore() {
        for ((path, aside) in taken.asReversed()) {
            runCatching { removeAny(path) }
            if (aside != null) runCatching { rename(aside, path) }
        }
    }
}

/** Run [body] with fresh [Claims], discarded when it returns and restored when it throws. */
internal inline fun <T> withClaims(body: (Claims) -> T): T {
    val claims = Claims()
    var placed = false
    try {
        return body(claims).also { placed = true }
    } finally {
        if (placed) claims.discard() else claims.restore()
    }
}

/** `.<name>.ketch-aside` beside [path]: hidden on Unix, so a bin directory on PATH never offers it as a command while it exists. */
internal fun asideName(path: Path): Path = withFileName(path, ".${path.name}.ketch-aside")

/** Rust `fs::rename`: one `rename(2)`, replacing an existing file. */
internal fun rename(
    from: Path,
    to: Path,
) = SystemFileSystem.atomicMove(from, to)

/** Rust `Path::with_file_name`. */
internal fun withFileName(
    path: Path,
    name: String,
): Path = path.parent?.let { Path(it, name) } ?: Path(name)
