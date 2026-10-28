// Unix symlink, mode and ownership helpers shared by the macOS and Linux backends. macOS-only app placement stays in
// the macOS backend.
package ketch.core.platform

import fs.EntryKind
import fs.FsException
import fs.Links
import fs.WalkEntry
import fs.canon
import fs.copyTree
import fs.createDirAll
import fs.entryKind
import fs.joinPath
import fs.mode
import fs.readLink
import fs.removeAny
import fs.setMode
import fs.walk
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.ioAt
import ketch.core.extra.ExtraPlacement
import ketch.core.extra.resolveUnder
import ketch.core.extract.isProgramHead
import ketch.core.extract.readHead
import ketch.core.extract.resolvesInside
import ketch.core.model.BinSpec
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.model.PackageKind
import ketch.core.model.globMatch
import ketch.core.model.globPreferred
import ketch.core.model.rustComponents
import ketch.core.report.Report
import ketch.core.text.asciiLowercase
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.files.Path

/** Directories inside a payload that never hold the program itself. */
internal val NOISE_DIRS =
    setOf(
        "share",
        "doc",
        "docs",
        "man",
        "completions",
        "complete",
        "etc",
        "lib",
        "include",
        "licenses",
        "_internal",
        "resources",
        "plugins",
    )

private val OWNER_READ_EXECUTE = "500".toInt(8)

/**
 * Add the bits a program needs to run, leaving every other bit alone.
 *
 * Owner read comes with the execute bits: an archive can name a script member `0o200`, and the kernel wants to read a
 * script before it runs it — an execute-only payload installs, records and then fails with "Permission denied" the
 * first time it is used.
 */
fun ensureExecutable(path: Path) {
    val bits = ioAt(path) { mode(path) ?: throw FsException.ofErrno(path) }
    if (bits and OWNER_READ_EXECUTE == OWNER_READ_EXECUTE) return
    ioAt(path) { setMode(path, bits or OWNER_READ_EXECUTE) }
}

/** Create [link] holding [target] as written. */
fun symlink(
    target: Path,
    link: Path,
) = ioAt(link) { fs.symlink(target, link) }

/**
 * Whether what is at a recorded path is still what ketch put there.
 *
 * A symlink carries its own evidence: it has to point where the record says. A copied `.app` leaves no mark on disk
 * at all, which is why the record exists — but only a directory can be one, and a matching record must never
 * authorize deleting a file the user put in its place.
 */
internal fun stillPlaced(record: LinkRecord): Boolean =
    when (record.kind) {
        LinkKind.Symlink, LinkKind.LinkedApp -> readLink(record.link)?.let(::rustComponents) == rustComponents(record.target)
        LinkKind.CopiedFile -> entryKind(record.link) == EntryKind.File
        LinkKind.CopiedApp -> entryKind(record.link) == EntryKind.Dir && entryKind(canon(record.target)) != null
    }

/**
 * Whether an occupied destination is this package's own to replace.
 *
 * Two kinds of evidence. A symlink pointing into [owned] — the package's directory in the store, covering every
 * version of it — was made by ketch for this package. A copied `.app` leaves no mark on disk at all, so the only
 * evidence there is the record written when it was placed.
 *
 * Either way the disk has the last word: a record whose path now holds something else is stale — the user replaced
 * it, or another package took the name over — and replacing that would destroy work ketch never made.
 *
 * Anything else is somebody else's: another package that claims the same binary name, or an application the user
 * installed themselves. Taking one over silently means uninstalling this package later deletes it.
 */
internal fun isOurs(
    link: Path,
    owned: Path,
    recorded: List<LinkRecord>,
): Boolean {
    if (recorded.any { rustComponents(it.link) == rustComponents(link) && stillPlaced(it) }) return true
    // Lexical `startsWith` alone would treat `owned/../../elsewhere` as ours.
    val target = readLink(link)
    return target != null && ".." !in rustComponents(target) && pathIsWithin(target, owned)
}

/** Clear a destination, or explain who already has it. What was there moves aside into [claims], so a placement that fails later can put it back. */
internal fun clearDestination(
    link: Path,
    owned: Path,
    recorded: List<LinkRecord>,
    claims: Claims,
) {
    destinationAvailable(link, owned, recorded)
    claims.take(link)
}

internal fun destinationAvailable(
    link: Path,
    owned: Path,
    recorded: List<LinkRecord>,
) {
    if (entryKind(link) != null && !isOurs(link, owned, recorded)) {
        throw KetchError.Msg("$link already exists and was not installed by ketch for this package; move it aside first")
    }
}

/** Why [dir] cannot take new files, or null when it can. */
internal fun writable(dir: Path): String? = if (entryKind(canon(dir)) == null) "does not exist" else probeWritable(dir)

/** Rust `source::local::copy_tree` on Unix: the tree as it is, links kept as written. */
internal fun copyLocalTree(
    src: Path,
    dest: Path,
) = try {
    copyTree(src, dest, Links.Keep)
} catch (e: FsException) {
    throw KetchError.fsKit(src, e)
}

/** Linux copies with the local-source tree copy when a rename cannot cross filesystems. */
internal fun moveIntoStore(
    payload: Path,
    store: Path,
) = swapIntoStore(payload, store, ::copyLocalTree)

/** Keep a payload symlink if its target is a regular file inside the tree. */
internal fun payloadExecutableEntry(
    entry: WalkEntry,
    root: Path,
): Path? =
    when (entry.kind) {
        EntryKind.File -> entry.path

        // One hop is not enough: `tool -> abs` reads as inside while `abs`, or any link further along, leads out of
        // the payload.
        EntryKind.Symlink -> entry.path.takeIf { resolvesInside(it, root) && entryKind(canon(it)) == EntryKind.File }

        else -> null
    }

/**
 * True when the immediate parent directory is named `bin` (any ASCII case). Windows archives and some toolchain
 * layouts ship `Bin/`; treating only the lowercase spelling as special left those packages falling back to noisy
 * top-level discovery.
 */
internal fun parentDirIsBin(path: Path): Boolean = path.parent?.name?.eqIgnoreAsciiCase("bin") == true

/**
 * Every executable file in the payload that is a plausible entry point, the package-named binary first. [bundles]
 * skips `.app` and `.framework` directories, which only Unix payloads hold programs inside.
 */
internal fun discoverExecutables(
    platform: Platform,
    root: Path,
    pkg: String,
    bundles: Boolean = true,
): List<Path> {
    val found =
        walk(root, maxDepth = 4, onError = {}) { entry ->
            val name = entry.path.name.asciiLowercase()
            entry.depth == 0 || !((bundles && (name.endsWith(".app") || name.endsWith(".framework"))) || name in NOISE_DIRS)
        }.mapNotNull { payloadExecutableEntry(it, root) }
            .filter { platform.isExecutable(it) }
            .toList()
    val inBin = found.filter(::parentDirIsBin)
    return orderDiscoveredExecutables(inBin.ifEmpty { found }, pkg)
}

/**
 * Resolve the manifest's explicit binary list against the extracted payload. [named] says whether a file name is the
 * one a `name`-only entry asks for, and [linkName] turns the entry's name into the link's.
 */
internal fun resolveBinSpecs(
    root: Path,
    specs: List<BinSpec>,
    named: (file: String, want: String) -> Boolean = { file, want -> file == want },
    linkName: (String) -> String = { it },
): List<Pair<Path, String>> {
    val rootParts = rustComponents(root)
    val candidates =
        walk(root, maxDepth = 6, onError = {}).filter { it.kind == EntryKind.File }.map { it.path }.toList()
    return specs.map { spec ->
        val pattern = spec.path
        val matched =
            if (pattern != null) {
                val hits =
                    candidates.filter { path ->
                        val parts = rustComponents(path)
                        parts.take(rootParts.size) == rootParts && globMatch(pattern, parts.drop(rootParts.size).joinToString("/"))
                    }
                globPreferred(root, pattern, hits, spec.name)
            } else {
                candidates.firstOrNull { named(it.name, spec.name.orEmpty()) }
            }
        val path =
            matched ?: throw KetchError.Msg(
                "manifest expects `${spec.path ?: spec.name ?: "<unnamed>"}` but the release payload does not contain it",
            )
        path to linkName(spec.name ?: path.name)
    }
}

internal fun linkBinary(
    target: Path,
    binDir: Path,
    name: String,
    owned: Path,
    recorded: List<LinkRecord>,
    claims: Claims,
): LinkRecord {
    ioAt(binDir) { createDirAll(binDir) }
    val link = joinPath(binDir, name)
    clearDestination(link, owned, recorded, claims)
    ensureExecutable(target)
    symlink(target, link)
    return LinkRecord(link, target, LinkKind.Symlink, LinkRole.Binary)
}

/** The binaries to link and their link names: the manifest's list, or what discovery found. */
internal fun binaryTargets(
    platform: Platform,
    root: Path,
    plan: Placement,
): List<Pair<Path, String>> {
    if (plan.binSpecs.isNotEmpty()) return resolveBinSpecs(root, plan.binSpecs)
    val found = discoverExecutables(platform, root, plan.name)
    return found.map { path ->
        val linkName = if (found.size == 1 && looksLikeBuildArtifact(path.name)) plan.name else path.name
        path to linkName
    }
}

private fun cliTargets(
    platform: Platform,
    root: Path,
    plan: Placement,
) = if (plan.kind == PackageKind.App) emptyList() else binaryTargets(platform, root, plan)

/** Refuse a second entry for one destination, then check nobody else owns it. */
internal fun claimDestination(
    destinations: MutableSet<String>,
    link: Path,
    owned: Path,
    plan: Placement,
) {
    if (!destinations.add(link.toString())) throw KetchError.Msg("multiple payload entries want to create $link")
    destinationAvailable(link, owned, plan.replacing)
}

/**
 * Move the payload into the store and run [linkAll] over it, which appends what it creates to the list it is given.
 *
 * Every destination is checked by [preflight] before anything moves. When linking fails partway, the links made so
 * far go, the previous occupants come back, and so does the version this call swapped out; on success both of those
 * are dropped. [copy] is the tree copy for a store on another filesystem.
 */
internal fun placeIntoStore(
    plan: Placement,
    copy: (Path, Path) -> Unit,
    preflight: (owned: Path) -> Unit,
    linkAll: (owned: Path, claims: Claims, links: MutableList<LinkRecord>) -> Unit,
): List<LinkRecord> {
    // Every version of this package lives under here. The version being replaced still owns its links at this point:
    // install retires them only once placement has succeeded.
    val packageDir = plan.storeDir.parent ?: plan.storeDir
    if (plan.link) preflight(packageDir)
    val swapped = swapIntoStore(plan.payloadDir, plan.storeDir, copy)
    if (!plan.link) {
        if (swapped) finalizeStoreSwap(plan.storeDir)
        return emptyList()
    }
    val links = mutableListOf<LinkRecord>()
    val claims = Claims()
    var placed = false
    try {
        linkAll(packageDir, claims, links)
        links += linkPlannedExtras(plan.extras, plan.storeDir, packageDir, plan.replacing, claims)
        if (links.isEmpty()) throw KetchError.EmptyPayload(plan.storeDir)
        placed = true
    } finally {
        if (placed) {
            claims.discard()
        } else {
            // Our own links go first: restoring the claims puts the previous occupants back at those same paths.
            runCatching { unplace(links, Report.silent()) }
            claims.restore()
        }
        if (swapped && placed) finalizeStoreSwap(plan.storeDir)
        if (swapped && !placed) restoreRetiredStore(plan.storeDir)
    }
    return links
}

/** Place CLI binaries only: bin-dir symlinks, never an `.app`. */
internal fun placeCli(
    platform: Platform,
    plan: Placement,
): List<LinkRecord> =
    placeIntoStore(
        plan,
        ::copyLocalTree,
        preflight = { owned ->
            val destinations = mutableSetOf<String>()
            for ((_, name) in cliTargets(platform, plan.payloadDir, plan)) claimDestination(destinations, joinPath(plan.binDir, name), owned, plan)
            if (destinations.isEmpty()) throw KetchError.EmptyPayload(plan.payloadDir)
            preflightExtras(plan, owned)
        },
    ) { owned, claims, links ->
        for ((target, name) in cliTargets(platform, plan.storeDir, plan)) {
            links += linkBinary(target, plan.binDir, name, owned, plan.replacing, claims)
        }
    }

/** The bit alone is not enough: tarballs routinely ship +x READMEs. */
internal fun isUnixExecutable(path: Path): Boolean {
    val bits = mode(path) ?: return false
    return entryKind(canon(path)) == EntryKind.File &&
        bits and ANY_EXECUTE != 0 &&
        runCatching { isProgramHead(readHead(path)) }.getOrDefault(false)
}

private val ANY_EXECUTE = "111".toInt(8)

/** The `root`, `store` and `bin` lines of `ketch doctor`: each directory must take new files. */
internal fun writableChecks(dirs: List<Pair<String, Path>>): List<DoctorCheck> =
    dirs.map { (label, dir) ->
        val why = writable(dir)
        if (why == null) {
            DoctorCheck.ok(label, "$dir is writable")
        } else {
            DoctorCheck.fail(label, "$dir: $why", "mkdir -p $dir && chmod u+w $dir")
        }
    }

/** Undo `place`. Must tolerate links that are already gone. */
internal fun unplace(
    links: List<LinkRecord>,
    report: Report,
) {
    for (record in links.filter { entryKind(it.link) != null }) {
        if (!stillPlaced(record)) {
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

/** Confirm extra destinations are ours to take, and that the payload files exist. */
internal fun preflightExtras(
    plan: Placement,
    owned: Path,
) {
    val dests = mutableSetOf<String>()
    for (extra in plan.extras) {
        resolveUnder(plan.payloadDir, extra.relPath)
        if (!dests.add(extra.dest.toString())) throw KetchError.Msg("multiple extra_paths want to create ${extra.dest}")
        destinationAvailable(extra.dest, owned, plan.replacing)
    }
}

/** Symlink classified extras from [root] into their user destinations. */
internal fun linkPlannedExtras(
    extras: List<ExtraPlacement>,
    root: Path,
    owned: Path,
    recorded: List<LinkRecord>,
    claims: Claims,
): List<LinkRecord> =
    extras.map { extra ->
        val target = resolveUnder(root, extra.relPath)
        extra.dest.parent?.let { parent -> ioAt(parent) { createDirAll(parent) } }
        clearDestination(extra.dest, owned, recorded, claims)
        symlink(target, extra.dest)
        LinkRecord(extra.dest, target, LinkKind.Symlink, extra.role)
    }
