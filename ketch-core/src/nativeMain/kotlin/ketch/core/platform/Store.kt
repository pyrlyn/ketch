package ketch.core.platform

import fs.createDirAll
import fs.entryKind
import fs.removeAny
import ketch.core.error.KetchError
import ketch.core.error.io
import ketch.core.error.ioAt
import ketch.core.model.rustComponents
import ketch.core.text.asciiLowercase
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * Whether [candidate] is an aside the Windows backend made of a file named [linkName]: `<linkName>.old.<pid>-<millis>`,
 * the name a running `.exe` is renamed to so its replacement can be copied in.
 *
 * Matched this narrowly because whatever matches is deleted: a user's own `tool.exe.old.backup` beside the bin copy is
 * not ketch's. Case-insensitive in the name, as the Windows file system is. Here rather than in the Windows backend so
 * the rule is tested on every host.
 */
internal fun isBusyAsideOf(
    linkName: String,
    candidate: String,
): Boolean {
    val named = candidate.length >= linkName.length && candidate.take(linkName.length).asciiLowercase() == linkName.asciiLowercase()
    val rest = candidate.substring(linkName.length.coerceAtMost(candidate.length))
    val stamp = rest.takeIf { named && it.startsWith(".old.") }?.removePrefix(".old.")?.split('-', limit = 2)
    return stamp?.size == 2 && stamp.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }
}

/**
 * A path next to [original] whose last component is that name plus [suffix].
 *
 * Store swaps rename `.incoming` and `.old` beside the live directory, which stays on one filesystem. A path with no
 * file name (the root) still gets a name.
 */
internal fun sibling(
    original: Path,
    suffix: String,
): Path {
    val name = rustComponents(original).lastOrNull()?.takeIf { it.isNotEmpty() && it != ".." } ?: "pkg"
    return withFileName(original, name + suffix)
}

/**
 * Move [payload] to [store], retiring a previous tree to `.old`.
 *
 * The replacement is assembled beside the destination and swapped in last. Deleting the old directory first would
 * mean an upgrade that fails while copying leaves no working version. [copy] runs only when the cache and the store
 * are on different filesystems and a rename cannot cross them.
 *
 * Returns true when it retired a previous tree. Only then may the caller finalize or restore: a `.old` found otherwise
 * is an earlier run's leftover, and restoring it would replace the live prefix with it. `.old` stays until links
 * succeed, so a failed place can put the working version back.
 */
internal fun swapIntoStore(
    payload: Path,
    store: Path,
    copy: (Path, Path) -> Unit,
): Boolean {
    // `relink` re-runs placement over a payload that is already in the store; without this the swap below would move
    // it out from under itself.
    if (rustComponents(payload) == rustComponents(store)) return false
    store.parent?.let { parent -> ioAt(parent) { createDirAll(parent) } }

    val staged = sibling(store, ".incoming")
    runCatching { removeAny(staged) }
    try {
        rename(payload, staged)
    } catch (_: IOException) {
        copy(payload, staged)
    }

    return if (entryKind(store) != null) {
        retireAndSwap(staged, store)
    } else {
        ioAt(store) { rename(staged, store) }
        false
    }
}

/** Retire the live [store] to `.old` and put [staged] in its place, or put the live one back. */
private fun retireAndSwap(
    staged: Path,
    store: Path,
): Boolean {
    val retired = sibling(store, ".old")
    runCatching { removeAny(retired) }
    ioAt(store) { rename(store, retired) }
    try {
        rename(staged, store)
    } catch (e: IOException) {
        runCatching { rename(retired, store) }
        runCatching { removeAny(staged) }
        throw KetchError.io(store, e)
    }
    return true
}

/** Drop the retired previous version once placement has finished successfully. */
internal fun finalizeStoreSwap(store: Path) {
    runCatching { removeAny(sibling(store, ".old")) }
}

/** Put the version this call swapped out back, after place failed on the new tree. */
internal fun restoreRetiredStore(store: Path) {
    val retired = sibling(store, ".old")
    if (entryKind(retired) != null) {
        runCatching { removeAny(store) }
        runCatching { rename(retired, store) }
    }
}
