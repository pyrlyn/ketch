// The package registry: a GitHub repository laid out one folder per package.
//
// Every top-level folder names a package and holds a `ketch.toml` describing it. Anything else in the repository —
// README, licence, CI config — has no `ketch.toml` and is simply not a package, so the registry needs no index file that
// could drift out of step with its contents.
//
// `ketch update` downloads the repository and replaces the local copy under `<root>/registry`. Nothing fetches it
// implicitly: a package that resolves today must keep resolving offline tomorrow.
package ketch.core.registry

import fs.EntryKind
import fs.FsException
import fs.TempDir
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.listDir
import fs.readText
import fs.removeAny
import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.error.io
import ketch.core.extract.TarGzExtractor
import ketch.core.extract.unwrapSingleDir
import ketch.core.http.Http
import ketch.core.model.Manifest
import ketch.core.model.normalizeName
import ketch.core.model.nowUnix
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.report.Stage
import ketch.core.source.apiBase
import ketch.core.source.copyTree
import ketch.core.text.BYTE_ORDER
import ketch.core.tomlfile.Document
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid

/** The file inside a package folder. The folder already names the package, so the file does not have to repeat it. */
const val PACKAGE_FILE = "ketch.toml"

/** True once a copy has been fetched. */
fun exists(cfg: Config) = isDir(cfg.registryDir)

/** Every package in the local copy, each paired with the file it came from. */
fun load(cx: Ctx): List<Pair<Manifest, Path>> = loadDir(cx.cfg.registryDir, cx.report)

/**
 * Fetch the registry and swap it in, returning how many packages it holds.
 *
 * The download is staged and only moved into place once it parses, so a bad or truncated fetch leaves the working copy
 * alone.
 */
fun update(cx: Ctx): Int {
    val cfg = cx.cfg
    val repo = cfg.registry
    cx.report.stage("registry", Stage.Downloading)
    cx.report.step("updating", "registry $repo")
    // Staged inside the root, so the finished tree can be renamed into place without crossing a filesystem.
    return fsKitAt(cfg.root) { TempDir.createIn(cfg.root) }.use { staging ->
        val tarball = Path(staging.path, "registry.tar.gz")
        // The API tarball endpoint follows the default branch and honours the token, which keeps unauthenticated rate
        // limits out of the way. It answers 415 to the octet-stream `Accept` that asset downloads use, so ask for the
        // API media type and let it redirect to the gzip.
        val headers = mapOf("Accept" to "application/vnd.github+json")
        cx.report.download("download").use { progress ->
            Http(cx).download(tarballUrl(repo), tarball, headers, true, progress, cx.cancel)
        }
        val unpacked = Path(staging.path, "tree")
        fsKitAt(unpacked) { createDirAll(unpacked) }
        cx.report.stage("registry", Stage.Extracting)
        // The tarball download already drew a byte bar. Unpacking has no member count ahead of time, so this is a
        // spinner rather than a second bar.
        cx.report.activity("extracting registry").run { TarGzExtractor.extract(tarball, unpacked) }
        // GitHub wraps the tree in one `owner-repo-<sha>` directory.
        swapIn(cx, unwrapSingleDir(unpacked), repo)
    }
}

/**
 * Where the registry's tarball is fetched from: the same API base every other GitHub request honors, so
 * `KETCH_GITHUB_API` stands in an Enterprise host here too.
 */
internal fun tarballUrl(repo: String) = "${apiBase()}/repos/$repo/tarball"

/**
 * Move a freshly-unpacked tree into place, returning its package count.
 *
 * A tree with no packages is refused: a repository that moved, emptied or answered with something unexpected must not
 * wipe a working registry.
 */
internal fun swapIn(
    cx: Ctx,
    tree: Path,
    repo: String,
): Int {
    val cfg = cx.cfg
    val packages = loadDir(tree, cx.report)
    collisions(packages).forEach(cx.report::warn)
    if (packages.isEmpty()) {
        throw KetchError.Msg("$repo has no package folders containing `$PACKAGE_FILE` — leaving the current registry in place")
    }
    replaceDir(cfg.registryDir, tree) { from, to -> SystemFileSystem.atomicMove(from, to) }
    writeMeta(cfg, UpdateMeta(repo, gitRevision(tree), null, nowUnix()))
    return packages.size
}

/**
 * Put [tree] at [live], keeping the previous [live] until the new one is in.
 *
 * [rename] is a parameter only so a test can make the renames fail; every caller passes an atomic move.
 */
internal fun replaceDir(
    live: Path,
    tree: Path,
    rename: (Path, Path) -> Unit,
) {
    if (entryKind(live) == null) return renameAt(live) { rename(tree, live) }
    // Move the working copy aside first. Deleting it before the new tree is in place would leave no registry if the
    // second rename failed (EXDEV, EACCES, ENOSPC) or if another ketch ran in the gap.
    val aside = asidePath(live)
    if (entryKind(aside) != null) fsKitAt(aside) { removeAny(aside) }
    renameAt(live) { rename(live, aside) }
    val failed =
        try {
            rename(tree, live)
            null
        } catch (e: IOException) {
            e
        }
    if (failed == null) {
        runCatching { removeAny(aside) }
        return
    }
    restore(live, aside, failed, rename)
    throw KetchError.io(live, failed)
}

/**
 * Put the aside copy back after the new tree could not be placed. Whatever stopped the rename back may not stop a copy
 * (a rename cannot cross filesystems; a copy can), and with no live registry every later resolve falls back to the
 * built-in tier.
 */
private fun restore(
    live: Path,
    aside: Path,
    failed: IOException,
    rename: (Path, Path) -> Unit,
) {
    val rollback = runCatching { rename(aside, live) }.exceptionOrNull() ?: return
    val copy = runCatching { copyTree(aside, live) }.exceptionOrNull()
    // The aside is now the only complete copy: keep it and say where.
    if (copy != null) {
        throw KetchError.Msg(
            "failed to place the new registry at $live (${failed.message}); also failed to restore the previous copy " +
                "(${rollback.message}; copying it back: ${copy.message}); it is kept at $aside",
        )
    }
    runCatching { removeAny(aside) }
}

private inline fun renameAt(
    path: Path,
    body: () -> Unit,
) = try {
    body()
} catch (e: IOException) {
    throw KetchError.io(path, e)
}

/** Sibling of the live registry, unique per process so a leftover aside from a crashed run is not the path this swap uses. */
internal fun asidePath(registryDir: Path): Path {
    val sibling = ".${registryDir.name}.aside-${getpid()}"
    return registryDir.parent?.let { Path(it, sibling) } ?: Path(sibling)
}

/** One package file that failed [checkTree]. */
data class ValidationError(
    /** The `ketch.toml` path, or the registry root for tree-wide problems. */
    val path: String,
    /** What went wrong. */
    val message: String,
)

/** Outcome of validating a registry tree offline. */
data class CheckReport(
    /** Packages that parsed and passed [Manifest.validate]. */
    val packages: Int,
    /** Every parse, validation and collision problem found. */
    val errors: List<ValidationError>,
    /** Entries that parsed, including ones that later collide on a name. */
    val parsed: List<Pair<Manifest, Path>>,
)

/**
 * Validate every package folder under [dir] the way registry CI should.
 *
 * Unlike [loadDir], nothing is skipped: each broken `ketch.toml` and every name collision is collected, and a tree that
 * cannot be read at all is a report with an error in it rather than a failure — the caller can print that as JSON for a
 * machine either way.
 */
fun checkTree(dir: Path): CheckReport {
    val folders = candidatePackageDirs(dir)
    return when {
        !isDir(dir) -> unreadable(dir, "no such directory")
        folders.isEmpty() -> unreadable(dir, "no package folders containing `$PACKAGE_FILE`")
        else -> checkFolders(dir, folders)
    }
}

private fun checkFolders(
    dir: Path,
    folders: List<Path>,
): CheckReport {
    val packages = mutableListOf<Pair<Manifest, Path>>()
    val errors = mutableListOf<ValidationError>()
    for (folder in folders) {
        val path = Path(folder, PACKAGE_FILE)
        if (!isPackageFile(path)) {
            errors += ValidationError(path.toString(), "`$PACKAGE_FILE` must be a regular file, not a symlink")
            continue
        }
        try {
            packages += readPackage(path, folder.name) to path
        } catch (e: KetchError) {
            errors += ValidationError(path.toString(), messageWithoutPath(path, e))
        }
    }
    errors += collisions(packages).map { ValidationError(dir.toString(), it) }
    return CheckReport(packages.size, errors, packages)
}

/** A report holding one problem with the tree itself. */
private fun unreadable(
    dir: Path,
    message: String,
) = CheckReport(0, listOf(ValidationError(dir.toString(), message)), emptyList())

/**
 * What went wrong, without the path [CheckReport] prints in front of it. [readPackage] builds its errors around the
 * file they are about — `Io` leads with the path, `Parse` embeds it — and a caller that shows the path as well would
 * otherwise say everything twice.
 */
private fun messageWithoutPath(
    path: Path,
    error: KetchError,
): String = if (error is KetchError.Parse) error.detail else error.message.orEmpty().removePrefix("$path: ")

/**
 * Names that two packages both answer to.
 *
 * Nothing else can catch this: each folder is valid on its own, the loser is shadowed silently, and which one loses
 * depends on sort order. Callers decide the severity: [loadDir] and [update] warn so an already-published registry
 * keeps resolving, and [checkTree] turns the same messages into errors so they cannot merge.
 */
internal fun collisions(packages: List<Pair<Manifest, Path>>): List<String> {
    // The claim is keyed by the path that made it, not by the package name: two folders can both declare
    // `name = "foo"` (a `.git` suffix and its plain sibling, or a hand edit), and comparing names would read that as
    // one package claiming its own name.
    val claimed = mutableMapOf<String, Pair<String, Path>>()
    val out = mutableListOf<String>()
    for ((manifest, path) in packages) {
        for (name in listOf(manifest.name) + manifest.provides) {
            val (owner, ownerPath) = claimed.getOrPut(normalizeName(name)) { manifest.name to path }
            if (ownerPath == path) continue
            out +=
                if (owner == manifest.name) {
                    "`$name` is claimed by two packages named `$owner`, in `${folder(ownerPath)}` and `${folder(path)}`; only the first will resolve"
                } else {
                    "`$name` is claimed by both `$owner` and `${manifest.name}`; only `$owner` will resolve"
                }
        }
    }
    return out
}

/** What a folder is called, for a message that has to tell two packages with the same name apart. */
private fun folder(path: Path) = path.parent?.name?.takeIf { it.isNotEmpty() } ?: path.toString()

/** The local asset that stands in for [name] during an offline install: a file named [name], or a folder `name/` holding exactly one regular file. */
fun fixturePayload(
    fixture: Path,
    name: String,
): Path {
    val entry = Path(fixture, name)
    return when (entryKind(entry)) {
        null -> throw KetchError.Msg("no fixture for `$name`")
        EntryKind.File -> entry
        EntryKind.Dir -> onlyFile(entry, name)
        else -> throw KetchError.Msg("fixture `$name` must be a file or a directory of one file")
    }
}

private fun onlyFile(
    dir: Path,
    name: String,
): Path {
    val files = fsKitAt(dir) { listDir(dir) }.sortedWith(BYTE_ORDER).map { Path(dir, it) }.filter { fsKitAt(it) { entryKind(it) } == EntryKind.File }
    return when (files.size) {
        1 -> files[0]
        0 -> throw KetchError.Msg("fixture `$name` is an empty directory")
        else -> throw KetchError.Msg("fixture `$name` has more than one file")
    }
}

private fun loadDir(
    dir: Path,
    report: Report,
): List<Pair<Manifest, Path>> =
    candidatePackageDirs(dir).mapNotNull { folder ->
        val path = Path(folder, PACKAGE_FILE)
        val name = sanitize(folder.name)
        when {
            !isPackageFile(path) -> {
                report.warn("ignoring registry package `$name`: `$PACKAGE_FILE` is not a regular file")
                null
            }

            // One broken entry must not hide the rest of the registry.
            else -> {
                try {
                    readPackage(path, folder.name) to path
                } catch (e: KetchError) {
                    report.warn("ignoring registry package `$name`: ${e.message}")
                    null
                }
            }
        }
    }

/**
 * Top-level folders that contain a `ketch.toml`, including a symlink one. [checkTree] must see those links so it can
 * fail closed; [loadDir] warns and skips them.
 */
@Suppress("SwallowedException")
private fun candidatePackageDirs(dir: Path): List<Path> =
    try {
        listDir(dir).sortedWith(BYTE_ORDER).map { Path(dir, it) }.filter { isDir(it) && entryKind(Path(it, PACKAGE_FILE)) != null }
    } catch (_: FsException) {
        // A registry that cannot be read has no packages, which every caller already reports.
        emptyList()
    }

/**
 * Whether [path] is a real `ketch.toml` rather than a link to one.
 *
 * The tree is somebody else's: a folder whose package file is a symlink would have ketch read — and quote in a parse
 * error, in a warning, in a CI log — a file from outside the tree, and a dangling link would leave that folder never
 * validated while the run still passes. The fetched registry is guarded on the way in (the extractor's link checks),
 * so this covers the trees that arrive as files: a checkout, a working copy, registry CI.
 */
private fun isPackageFile(path: Path) = entryKind(path) == EntryKind.File

/** Rules a registry entry must pass beyond [Manifest.validate]. Shared with `push`, so push refuses the same manifest CI would reject. */
internal fun validateRegistryEntry(
    what: String,
    folder: String?,
    declaredName: String?,
    manifest: Manifest,
) {
    if (folder != null && declaredName != null && normalizeName(declaredName) != normalizeName(folder)) {
        throw KetchError.Parse(what, "declares name `$declaredName` but sits in folder `$folder`")
    }
    // A registry entry names a release anyone can fetch. `local:` would make a shared entry install from — or, for a
    // path that is not a plain file, hang on — the disk of whoever installs it, which is not a promise the registry can
    // make. User manifests and `ketch install local:…` are unaffected: they never pass through here.
    if (manifest.source.scheme == "local") {
        throw KetchError.Parse(what, "a registry package cannot install from a local path; use `github:owner/repo`")
    }
}

/**
 * Parse one package folder. The folder is the package name, so `name` in the file is optional — and when it is present
 * it must agree, or the package would be unreachable under the name its folder advertises.
 */
internal fun readPackage(
    path: Path,
    folder: String,
): Manifest {
    val what = path.toString()
    val doc = Document.parse(fsKitAt(path) { readText(path) }, what)
    val declared = doc.str("name")
    if (declared == null) doc.setStr("name", folder)
    val manifest = doc.deserialize<Manifest>()
    try {
        manifest.validate()
    } catch (e: KetchError) {
        throw KetchError.Parse(what, e.message.orEmpty(), e)
    }
    validateRegistryEntry(what, folder, declared, manifest)
    return manifest
}
