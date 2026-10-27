package ketch.core.selfupdate

import fs.EntryKind
import fs.canon
import fs.entryKind
import fs.isDir
import ketch.core.config.Config
import ketch.core.config.RENAMED_REPOS
import ketch.core.config.envOs
import ketch.core.model.rustComponents
import ketch.core.platform.DoctorCheck
import ketch.core.platform.dataHome
import ketch.core.platform.pathIsStrictWithin
import ketch.core.report.Report
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.files.Path
import process.Command
import process.ProcessException
import process.status

/*
 * The other owners a ketch can have — Homebrew, mise — and the asides a self command leaves behind. Each is told from
 * a path alone: asking `brew` or `mise` would mean running a program ketch did not install to learn what the path
 * already says.
 */

/** Warn when a previous self command's aside is still in the bin dir, or beside the running binary when that binary lives somewhere else. */
fun staleAsideCheck(cfg: Config): DoctorCheck? = staleAsideFrom(cfg.binDir, runCatching { currentExe() }.getOrNull())

internal fun staleAsideFrom(
    binDir: Path,
    running: Path?,
): DoctorCheck? {
    val paths = asideCandidates(Path(binDir, bootstrapBinaryName())).toMutableList()
    running?.let { exe -> asideCandidates(exe).filter { it !in paths }.forEach { paths += it } }
    val present = paths.filter { entryKind(canon(it)) == EntryKind.File }
    if (present.isEmpty()) return null
    val listed = present.joinToString(", ")
    val detail = if (present.size == 1) "$listed is a previous ketch left by self-update" else "$listed are previous ketch binaries left by self-update"
    return DoctorCheck.warn(
        "backup",
        detail,
        "The next `ketch self upgrade` removes a previous binary after the process renamed onto it has exited. Delete whatever remains by hand.",
    )
}

/**
 * The Homebrew cask's own directory, when ketch was installed with `brew`.
 *
 * Homebrew records a cask under `<prefix>/Caskroom/<token>`, so its presence is the question "did brew install
 * this?" answered without running anything.
 */
fun caskDir(): Path? = caskDirIn(brewPrefixes())

internal fun caskDirIn(prefixes: List<Path>): Path? = prefixes.map { Path(Path(it, "Caskroom"), SELF_NAME) }.firstOrNull(::isDir)

/**
 * Where Homebrew might live: its own answer first, then the two standard prefixes for Apple Silicon and Intel.
 *
 * `HOMEBREW_PREFIX` alone is not enough: a leftover Intel cask under `/usr/local` must still be found when the active
 * brew is Apple Silicon (and the reverse).
 */
private fun brewPrefixes(): List<Path> {
    val prefixes = listOfNotNull(envOs("HOMEBREW_PREFIX")?.takeIf { it.isNotEmpty() }?.let(::Path)).toMutableList()
    for (candidate in listOf("/opt/homebrew", "/usr/local").map(::Path)) if (candidate !in prefixes) prefixes += candidate
    return prefixes
}

/**
 * The `brew` that owns [cask]: the one in the prefix the cask was found under, since a machine can carry both an Apple
 * Silicon and an Intel Homebrew, and only one of them knows about this cask. `PATH` is the last resort.
 */
internal fun brewBinary(cask: Path): String =
    cask.parent
        ?.parent
        ?.let { Path(Path(it, "bin"), "brew") }
        ?.takeIf { entryKind(canon(it)) == EntryKind.File }
        ?.toString() ?: "brew"

/**
 * Hand the cask back to Homebrew, which is the only thing that can forget it. Deleting the Caskroom directory would
 * leave `brew` believing ketch is still installed, so this runs the real command and reports rather than guesses.
 */
internal fun removeCask(
    cask: Path,
    report: Report,
): Boolean {
    val brew = brewBinary(cask)
    report.step("removing", "the Homebrew cask")
    // The terminal is shared: `brew` prints its own progress, and asking it to be quiet would hide the sudo prompt it
    // may need.
    return runOwner(
        Command(brew, listOf("uninstall", "--cask", SELF_NAME)),
        report,
        failed = { "`brew uninstall --cask $SELF_NAME` failed ($it); run it by hand to finish removing the cask" },
        unrunnable = { "could not run $brew: $it" },
    )
}

/** A ketch that `mise use` installed: where it lives, and the tool name mise knows it by, which is what `mise unuse` needs. */
data class MiseInstall(
    val dir: Path,
    val tool: String,
)

/** The mise tool directory (`<data dir>/installs/<tool>`) holding the running binary, when ketch was installed with `mise use`. */
internal fun miseToolDir(): Path? = runCatching { currentExe() }.getOrNull()?.let { miseToolDirIn(it, miseDataDirs()) }

internal fun miseToolDirIn(
    exe: Path,
    dataDirs: List<Path>,
): Path? =
    dataDirs.firstNotNullOfOrNull { data ->
        // The data dir may itself sit behind a symlink; `exe` is canonical.
        val installs = canon(Path(data, "installs"))
        if (!pathIsStrictWithin(exe, installs)) return@firstNotNullOfOrNull null
        val depth = rustComponents(installs).size + 1
        generateSequence(exe) { it.parent }.firstOrNull { rustComponents(it).size == depth }
    }

/**
 * The name `mise unuse` takes for the tool in [dir].
 *
 * mise names an install directory after the tool with `:` and `/` turned into `-`, so `github:pyrlyn/ketch` lives in
 * `github-pyrlyn-ketch`. That is only reversible for a name that ends in this repository; anything else (a registry
 * short name such as `ketch`) is already the tool name. A copy mise installed before the repository moved sits in
 * `github-listepo-ketch` and mise's config still says `github:listepo/ketch`, so the old names in [RENAMED_REPOS]
 * count too and keep the name mise knows.
 */
internal fun miseToolName(
    dir: Path,
    selfRepo: String,
): String {
    val name = dir.name.ifEmpty { SELF_NAME }
    val oldNames = RENAMED_REPOS.filter { it.second.eqIgnoreAsciiCase(selfRepo) }.map { it.first }
    return (listOf(selfRepo) + oldNames).firstNotNullOfOrNull { repo ->
        name
            .removeSuffixOrNull(repo.replace('/', '-'))
            ?.removeSuffixOrNull("-")
            ?.takeIf { backend -> backend.isNotEmpty() && backend.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            ?.let { backend -> "$backend:$repo" }
    } ?: name
}

private fun String.removeSuffixOrNull(suffix: String) = if (endsWith(suffix)) dropLast(suffix.length) else null

/**
 * Hand the install back to mise, the only thing that can forget it: removing the directory would leave the tool in
 * mise's config, to be reinstalled on the next `mise install`. `-g` because that is how the README installs it.
 */
internal fun unuseMise(
    tool: String,
    report: Report,
): Boolean {
    report.step("removing", "$tool from mise")
    // As for brew, mise prints its own progress. `--yes` because the user has just answered this very question, and
    // mise would otherwise ask it again for every version it prunes.
    return runOwner(
        Command("mise", listOf("--yes", "unuse", "-g", tool)),
        report,
        failed = { "`mise unuse -g $tool` failed ($it); run it by hand to finish" },
        unrunnable = { "could not run mise ($it); run `mise unuse -g $tool` by hand" },
    )
}

/** Run another package manager on the shared terminal; [failed] words an unsuccessful exit, [unrunnable] a program that did not start. */
private fun runOwner(
    command: Command,
    report: Report,
    failed: (String) -> String,
    unrunnable: (String) -> String,
): Boolean =
    try {
        val status = command.status()
        if (!status.success) report.warn(failed(status.toString()))
        status.success
    } catch (e: ProcessException) {
        report.warn(unrunnable(e.detail.removePrefix("could not run ${command.program}: ")))
        false
    }

/** Where mise might keep its installs: its own override first, then the default it picks on this OS. */
private fun miseDataDirs(): List<Path> {
    val found = listOfNotNull(envOs("MISE_DATA_DIR")?.takeIf { it.isNotEmpty() }?.let(::Path)).toMutableList()
    // mise follows XDG on macOS too, not Application Support.
    val default = Path(dataHome(), "mise")
    if (default !in found) found += default
    return found
}
