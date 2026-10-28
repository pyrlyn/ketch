package ketch.core.install

import fs.FsException
import fs.canonicalize
import fs.entryKind
import fs.isDir
import fs.joinPath
import fs.listDir
import fs.removeAny
import ketch.core.config.Config
import ketch.core.error.fsKitAt
import ketch.core.model.InstalledPackage
import ketch.core.model.RetainedVersion
import ketch.core.model.Version
import ketch.core.model.rustComponents
import ketch.core.platform.pathIsStrictWithin
import ketch.core.report.Report
import ketch.core.state.State
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Record the replaced prefix instead of deleting it. Missing or out-of-store prefixes are not eligible: promising a
 * version that is already gone is worse than keeping nothing.
 *
 * The new version leaves the list too: upgrading back to a version kept since a rollback makes its prefix the live
 * one, and a retained entry still naming it would let `prune` delete the store every link points into.
 */
internal fun retainReplaced(
    cfg: Config,
    old: InstalledPackage,
    newPrefix: Path,
    newVersion: Version,
    retained: MutableList<RetainedVersion>,
) {
    retained.removeAll { it.prefix == old.prefix || it.version == old.version || it.prefix == newPrefix || it.version == newVersion }
    if (old.prefix == newPrefix || !isDir(old.prefix) || !isInsideStore(cfg.storeDir, old.prefix)) return
    retained.add(0, RetainedVersion.fromInstalled(old))
}

/**
 * Delete a store directory, and its now-empty package parent.
 *
 * Refuses anything outside the store: a corrupted state file must never turn an uninstall into a `rm -rf` of
 * somewhere else.
 */
internal fun removeStoreDir(
    cfg: Config,
    prefix: Path,
    report: Report,
) {
    if (!isInsideStore(cfg.storeDir, prefix)) {
        report.warn("refusing to remove $prefix — it is not inside the ketch store")
        return
    }
    try {
        removeAny(prefix)
    } catch (e: FsException) {
        report.warn("could not remove $prefix: ${e.message}")
    }
    // Only succeeds when empty.
    prefix.parent?.takeIf { it != cfg.storeDir }?.let { parent -> runCatching { SystemFileSystem.delete(parent) } }
}

/**
 * Remove the `<version>.incoming` and `<version>.old` folders an interrupted swap left in `store/<name>/`.
 *
 * Their own cleanup is best effort, so one that failed would otherwise wait for the next swap of the same version —
 * and a stale `.incoming` is where that swap stages the new payload. A prefix the package still records is never
 * touched, whatever its name ends with. Fails naming the leftover that could not be removed.
 */
internal fun sweepSwapLeftovers(
    cfg: Config,
    name: String,
    existing: InstalledPackage?,
) {
    val dir = packageDirCandidate(cfg, name) ?: return
    val entries =
        try {
            listDir(dir)
        } catch (_: FsException) {
            return
        }
    val recorded = existing?.let { pkg -> listOf(pkg.prefix) + pkg.retained.map { it.prefix } }.orEmpty()
    entries
        .filter { it.endsWith(".incoming") || it.endsWith(".old") }
        .map { joinPath(dir, it) }
        .filter { it !in recorded }
        // Removed without following a link, so a link is removed as a link and never followed out of the store.
        .forEach { path -> fsKitAt(path) { removeAny(path) } }
}

/**
 * `store/<name>/` when [name] is one plain path component and the folder exists inside the store after symlinks
 * resolve.
 *
 * The folder's own name on disk must be [name] byte for byte. A file system that folds case (APFS, NTFS) opens
 * `store/ripgrep` for `RipGrep`, and a lookup by path alone would hand an installed package's folder to whoever
 * spelled it nearly.
 */
internal fun packageDirCandidate(
    cfg: Config,
    name: String,
): Path? {
    val plain = name != ".." && rustComponents(Path(name)) == listOf(name)
    val dir = joinPath(cfg.storeDir, name)
    val inside = plain && entryKind(dir) != null && isInsideStore(cfg.storeDir, dir)
    val spelledSo = inside && runCatching { name in listDir(cfg.storeDir) }.getOrDefault(false)
    return dir.takeIf { spelledSo }
}

/**
 * `store/<name>/` when it is a folder no state entry accounts for: a candidate as [removePackageDir] takes it, holding
 * no prefix any installed package records. What `ketch uninstall` of an unknown name may offer to remove.
 */
fun leftoverPackageDir(
    cfg: Config,
    state: State,
    name: String,
): Path? {
    val dir = packageDirCandidate(cfg, name) ?: return null
    val recorded = state.iter().flatMap { pkg -> listOf(pkg.prefix) + pkg.retained.map { it.prefix } }
    return dir.takeIf { recorded.none { it == dir || isInsideStore(dir, it) } }
}

/**
 * Delete `store/<name>/` whole, with whatever is left in it.
 *
 * Returns the folder when it was a candidate — a direct child of the store named exactly like the package, and inside
 * it after symlinks resolve — so the caller knows which prefixes it already covered.
 */
fun removePackageDir(
    cfg: Config,
    name: String,
    report: Report,
): Path? {
    val dir = packageDirCandidate(cfg, name) ?: return null
    try {
        removeAny(dir)
    } catch (e: FsException) {
        report.warn("could not remove $dir: ${e.message}")
    }
    return dir
}

/** True when [prefix] is a proper subdirectory of [store], after resolving symlinks and rejecting `..` escapes a corrupted state file could invent. */
internal fun isInsideStore(
    store: Path,
    prefix: Path,
): Boolean {
    // When both exist, resolve symlinks so a decoy link inside the store cannot point a removal at an outside victim.
    val resolved = runCatching { canonicalize(store) to canonicalize(prefix) }.getOrNull()
    if (resolved != null) return pathIsStrictWithin(resolved.second, resolved.first)
    // Lexical fallback for a missing payload (idempotent uninstall). A root written with a `..` in it
    // (`KETCH_ROOT=../ketch`) carries that component in both paths, and refusing it there would quietly disable every
    // cleanup. Only the part below the store is checked for `..`.
    val below = rustComponents(prefix).drop(rustComponents(store).size)
    return pathIsStrictWithin(prefix, store) && below.isNotEmpty() && ".." !in below
}

/**
 * Deletes a store directory when closed, unless the install got far enough to [keep] it.
 *
 * Placement moves the payload into the store before creating any link, so a failure after that point — a binary
 * name another package already owns, a payload with nothing runnable in it — leaves a full store directory that no
 * state entry mentions: invisible to `ketch list`, out of reach of `ketch uninstall`, and taken for a finished
 * install by the next run.
 */
internal class ScopedDir(
    private var path: Path?,
) : AutoCloseable {
    fun keep() {
        path = null
    }

    override fun close() {
        path?.let { runCatching { removeAny(it) } }
    }
}
