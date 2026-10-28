package ketch.core.selfupdate

import fs.EntryKind
import fs.FsException
import fs.canon
import fs.entryKind
import fs.isDir
import fs.joinPath
import fs.removeAny
import fs.removeEmptyDir
import fs.removeFile
import fs.rename
import fs.systemTempDir
import ketch.core.config.Config
import ketch.core.config.HOST_WINDOWS
import ketch.core.config.homeDir
import ketch.core.error.KetchError
import ketch.core.install.uninstall
import ketch.core.model.rustComponents
import ketch.core.platform.pathIsWithin
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.shell.RegistryEntry
import ketch.core.shell.doskeyFile
import ketch.core.shell.filesWithBlock
import ketch.core.shell.powershellProfilesWithBlock
import ketch.core.shell.registryEntries
import ketch.core.shell.removeRegistryEntry
import ketch.core.shell.uninstallCmdMacros
import ketch.core.shell.uninstallFile
import ketch.core.shell.uninstallPowershellProfile
import ketch.core.state.Lock
import ketch.core.state.State
import kotlinx.io.files.Path
import platform.posix.getpid

/**
 * What [uninstallSelf] will remove, worked out before anything is touched.
 *
 * The plan exists so the question can name what is about to be lost. "remove ketch?" and "remove ketch, these four
 * packages, the block in your .zshrc and the Homebrew cask?" are different questions, and only the second one can be
 * answered honestly.
 */
data class UninstallPlan(
    /** Packages to uninstall properly, ketch itself included. */
    val packages: List<String> = emptyList(),
    /** The ketch root, when there is one to take apart. */
    val root: Path? = null,
    /** Shell startup files holding a ketch PATH block. */
    val shellFiles: List<Path> = emptyList(),
    /** Registry values ketch wrote that are still there. */
    val registry: List<RegistryEntry> = emptyList(),
    /** PowerShell profiles holding the block that loads ketch's completion. */
    val powershellProfiles: List<Path> = emptyList(),
    /** The Homebrew cask's own directory, when ketch came from `brew`. */
    val cask: Path? = null,
    /** The running binary, when it lives inside the root and so goes with it. */
    val exe: Path? = null,
    /** The mise install the running binary came from. Removed only when the caller leaves it set, which the command does after asking separately. */
    val mise: MiseInstall? = null,
)

/** Work out what removing ketch would take, without removing any of it. */
fun uninstallPlan(
    cfg: Config,
    keepPackages: Boolean,
    noBrew: Boolean,
): UninstallPlan {
    val state = State.load(cfg)
    return UninstallPlan(
        packages = if (keepPackages) listOfNotNull(state.get(SELF_NAME)?.name) else state.names(),
        // With `--keep-packages` the tree stays: the other packages live in it.
        root = cfg.root.takeIf { !keepPackages && isDir(it) },
        shellFiles = if (keepPackages) emptyList() else filesWithBlock(),
        // The user PATH is kept with `--keep-packages`, like the shell blocks: the packages left in the bin dir still
        // need it. What loads ketch itself (cmd's AutoRun) goes either way.
        registry = registryEntries(cfg).filter { !keepPackages || !it.servesPackages() },
        powershellProfiles = powershellProfilesWithBlock(),
        cask = if (noBrew) null else caskDir(),
        exe = runCatching { currentExe() }.getOrNull()?.takeIf { pathIsWithin(canon(it), canon(cfg.root)) },
        mise = miseToolDir()?.let { MiseInstall(it, miseToolName(it, cfg.selfRepo)) },
    )
}

/**
 * Carry out [plan], removing what it names. Returns the paths that are gone.
 *
 * Best effort past the first package: someone who has said yes to this wants ketch gone, and stopping halfway would
 * leave a tree they now have to take apart by hand. Every failure is warned about instead.
 *
 * The install lock stays held until the registry entries, the shell blocks and the root are gone. The cask step is
 * outside it: `brew uninstall` starts a nested `ketch self uninstall`, which has to be able to take the lock when
 * `--keep-packages` left the binary in place.
 */
fun uninstallSelf(
    cx: Ctx,
    plan: UninstallPlan,
): List<Path> {
    val removed = mutableListOf<Path>()
    val shellRemoved = mutableListOf<Path>()
    // Set when the root is finished off after this process exits: the running binary inside it is then that
    // process's to delete, not ours.
    var finishing: Path? = null
    Lock.acquire(cx).use {
        removePackages(cx, plan, removed)
        // The registry entries go before the root too: a Path entry spelled differently from the bin dir is matched by
        // resolving both, which needs the folder still there.
        removeLoaders(cx, plan, removed)
        // Shell blocks before the root too: removing the root unlinks the lock file, and these files live outside it.
        // Reported after the cask, which is where they have always appeared in the list this function returns.
        for (file in plan.shellFiles) warnOnFailure(cx.report, file.toString()) { if (uninstallFile(file)) shellRemoved += file }
        plan.root?.let { root ->
            val (gone, later) = removeRoot(cx, root)
            removed += gone
            if (later) finishing = root
        }
    }

    // After the root, so the nested `ketch self uninstall` the cask runs on its way out finds no binary and gives up
    // harmlessly instead of recursing. Also after the lock: that nested process takes the lock itself when the binary
    // is still there (`--keep-packages`).
    plan.cask?.let { if (removeCask(it, cx.report)) removed += it }
    removed += shellRemoved
    removeExe(cx, plan, finishing, removed)

    // Last: mise deletes the very binary running this, and on Windows that fails outright while it runs, so everything
    // else is done by then.
    plan.mise?.let {
        if (HOST_WINDOWS) moveOutOf(it.dir, cx.report)
        if (unuseMise(it.tool, cx.report)) removed += it.dir
    }
    return removed
}

/** Uninstall properly rather than deleting files: links and copied app bundles live outside the root and would otherwise be left dangling. */
private fun removePackages(
    cx: Ctx,
    plan: UninstallPlan,
    removed: MutableList<Path>,
) {
    val state = State.load(cx.cfg)
    for (name in plan.packages) warnOnFailure(cx.report, name) { removed += uninstall(cx, state, name).prefix }
    // Save first: if removing the tree fails, state still matches reality. The lock stays: it lives in the root, so
    // dropping it here would let another ketch rewrite a tree this run was still taking apart.
    state.save(cx.cfg)
}

/** The registry values, cmd's macro file and the PowerShell completion block: what Windows loads ketch and its bin dir from. */
private fun removeLoaders(
    cx: Ctx,
    plan: UninstallPlan,
    removed: MutableList<Path>,
) {
    for (entry in plan.registry) {
        try {
            removeRegistryEntry(cx.cfg, entry)
        } catch (e: KetchError) {
            cx.report.warn("${entry.describe()}: ${e.message}")
        }
    }
    // Before the root: the macro file lives in it, and the root is removed only when nothing ketch did not name is
    // left inside. A macro file AutoRun no longer names is still ketch's to delete.
    if (RegistryEntry.CmdMacros !in plan.registry && entryKind(doskeyFile(cx.cfg)) != null) {
        warnOnFailure(cx.report, "cmd macros") { uninstallCmdMacros(cx.cfg) }
    }
    for (file in plan.powershellProfiles) warnOnFailure(cx.report, file.toString()) { if (uninstallPowershellProfile(file)) removed += file }
}

/** The running binary: usually already gone with the store prefix or the bin dir, but a ketch copied in flat by an older installer is not. */
private fun removeExe(
    cx: Ctx,
    plan: UninstallPlan,
    finishing: Path?,
    removed: MutableList<Path>,
) {
    val exe = plan.exe
    when {
        // The process finishing the root after this one exits deletes it.
        exe != null && finishing != null && isWithin(exe, finishing) -> {}

        exe != null && entryKind(canon(exe)) != null -> {
            try {
                removeFile(exe)
                removed += exe
            } catch (e: FsException) {
                cx.report.warn("$exe: ${e.detail} (the running image is kept until nothing holds it)")
            }
        }

        // A ketch outside the root is not ketch's to delete: a development build, or a copy someone put on PATH
        // themselves. Nor is a mise install, but it has an owner that can take it away: when the user agreed, and the
        // command says how when not.
        exe == null && miseToolDir() == null -> {
            runCatching { currentExe() }.getOrNull()?.let { cx.report.note("$it is outside ${cx.cfg.root} and was kept") }
        }
    }
}

private inline fun warnOnFailure(
    report: Report,
    what: String,
    body: () -> Unit,
) {
    try {
        body()
    } catch (e: KetchError) {
        report.warn("$what: ${e.message}")
    }
}

/**
 * [path] is [root] or below it, compared on resolved paths where both exist: a root spelled with a Windows short name
 * (`RUNNER~1`) must still contain the binary spelled with the long one.
 */
private fun isWithin(
    path: Path,
    root: Path,
): Boolean = pathIsWithin(canon(path), canon(root))

/** What was removed, and whether the rest is removed once this process has exited. */
private fun removeRoot(
    cx: Ctx,
    root: Path,
): Pair<List<Path>, Boolean> = removeRootAt(cx, root, homeDir()) { r, left -> finishAfterExit(r, left, cx.report) }

/**
 * Take the root apart by naming what ketch owns, then removing the directory itself only if nothing else is left in
 * it. [finish] is handed what could not be removed now and says whether it will be removed later.
 *
 * Never a recursive delete of the root: `install.sh --install-dir ~/bin` makes the root the parent of that directory,
 * which is the user's home in the worst case. When the root *is* the home directory, the named children (`bin`,
 * `store`, `cache`, …) are not emptied either — they are shared with the rest of the account, and neither are those of
 * any root ketch did not create; see [ownsRoot]. A dedicated root like `~/.ketch` is still wiped.
 */
internal fun removeRootAt(
    cx: Ctx,
    root: Path,
    home: Path?,
    finish: (Path, List<Path>) -> Boolean,
): Pair<List<Path>, Boolean> {
    val cfg = cx.cfg
    val wipe = ownsRoot(cfg, home)
    val removed = mutableListOf<Path>()
    val left = mutableListOf<Pair<Path, FsException>>()
    val dirs = listOf(cfg.binDir, cfg.storeDir, cfg.cacheDir, cfg.manifestDir, cfg.pluginDir, cfg.registryDir)
    for (dir in dirs) removeOwnedDir(dir, wipe, removed, left, cx.report)
    // The log directory holds the file being written to as this runs, so it goes whole and last among the
    // directories.
    cfg.logFile.parent?.let { removeOwnedDir(it, wipe, removed, left, cx.report) }
    if (wipe) {
        val files = listOf(cfg.stateFile, cfg.statsDb, cfg.configFile, cfg.lockFile, cfg.registryMeta, cfg.rootMarker())
        for (file in files) removeOwnedFile(file, removed, left)
    }
    if (left.isNotEmpty()) {
        // Only a failure nobody will retry is worth a warning.
        if (finish(root, left.map { it.first })) {
            cx.report.note("$root is removed once this ketch has exited")
            return removed to true
        }
        for ((path, e) in left) cx.report.warn("$path: ${e.detail}")
    }
    if (wipe) removeEmptyRoot(root, left.isNotEmpty(), removed, cx.report)
    return removed to false
}

private fun removeOwnedFile(
    file: Path,
    removed: MutableList<Path>,
    left: MutableList<Pair<Path, FsException>>,
) {
    try {
        removeFile(file)
        removed += file
    } catch (e: FsException) {
        if (e.kind != FsException.Kind.NotFound) left += file to e.withoutPath()
    }
}

private fun removeEmptyRoot(
    root: Path,
    holdingLeftovers: Boolean,
    removed: MutableList<Path>,
    report: Report,
) {
    try {
        removeEmptyDir(root)
        removed += root
    } catch (e: FsException) {
        // Missing is done; a root still holding what the warnings named needs no second word; anything else left is
        // something ketch did not write, so say so and leave it.
        if (e.kind != FsException.Kind.NotFound && !holdingLeftovers) report.note("$root was left in place: it holds files ketch did not put there")
    }
}

/**
 * Whether the named children of the root are ketch's to empty.
 *
 * Only a root ketch created (it carries the marker) or the default `~/.ketch` qualifies: a root like `/usr/local` or
 * `~/.local` that existed before ketch shares `bin/` and `cache/` with other programs. The home directory, or anything
 * holding it, never does, whatever it carries.
 */
private fun ownsRoot(
    cfg: Config,
    home: Path?,
): Boolean =
    when {
        home != null && containsDir(cfg.root, home) -> false
        home != null && sameDir(cfg.root, joinPath(home, ".ketch")) -> true
        else -> entryKind(canon(cfg.rootMarker())) == EntryKind.File
    }

/**
 * Resolved and case-folded, so a symlink or another spelling of the same directory compares equal. Folding on a
 * case-sensitive file system can only call two directories the same, which errs towards keeping files.
 */
private fun folded(path: Path): List<String> = rustComponents(Path(canon(path).toString().lowercase()))

internal fun sameDir(
    a: Path,
    b: Path,
) = folded(a) == folded(b)

/** [outer] is [inner] or one of its ancestors. */
internal fun containsDir(
    outer: Path,
    inner: Path,
): Boolean {
    val o = folded(outer)
    return folded(inner).take(o.size) == o
}

private fun removeOwnedDir(
    dir: Path,
    wipe: Boolean,
    removed: MutableList<Path>,
    left: MutableList<Pair<Path, FsException>>,
    report: Report,
) {
    if (entryKind(dir) == null) return
    try {
        if (wipe) removeAny(dir) else removeEmptyDir(dir)
        removed += dir
    } catch (e: FsException) {
        when {
            e.kind == FsException.Kind.NotFound -> Unit
            wipe -> left += dir to e.withoutPath()
            else -> report.note("$dir was left in place: it holds files ketch did not put there")
        }
    }
}

/** Hand [left] and then [root] to a process that removes them once this one has exited; whether one will. */
internal expect fun finishAfterExit(
    root: Path,
    left: List<Path>,
    report: Report,
): Boolean

/**
 * Windows refuses to delete a running image but lets it be renamed, and mise removes its tree with this binary still
 * running from it. The process keeps its handle either way. The temp dir is on the same volume as the profile mise
 * lives in, so this is a rename, not a copy, and the file is left for the system's own temp cleanup: nothing else can
 * delete it while this process still runs.
 */
private fun moveOutOf(
    dir: Path,
    report: Report,
) {
    val exe = runCatching { currentExe() }.getOrNull() ?: return
    if (!pathIsWithin(exe, dir)) return
    try {
        rename(exe, joinPath(systemTempDir(), "ketch-uninstalled-${getpid()}.exe"))
    } catch (e: FsException) {
        report.warn("could not move $exe out of mise's tree (${e.detail}); mise may fail to remove it")
    }
}
