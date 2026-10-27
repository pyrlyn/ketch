package ketch.core.shell

import fs.FsException
import fs.WriteOptions
import fs.canon
import fs.readText
import fs.textOrEmpty
import ketch.core.config.Config
import ketch.core.config.env
import ketch.core.config.homeDir
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.platform.DoctorCheck
import kotlinx.io.files.Path

/** What [install] or [uninstall] did to one shell's config file. */
enum class Outcome {
    /** The block was written for the first time. */
    Added,

    /** An existing ketch block named a different directory and was rewritten. */
    Updated,

    /** The block was taken out again. */
    Removed,

    /** Nothing to do: already correct, or the user had set it up by hand. */
    Unchanged,
}

/** One shell's config file, and what happened to it. */
data class Change(
    val shell: Shell,
    val file: Path,
    val outcome: Outcome,
)

/** The login shell, from `$SHELL`. */
fun current(): Shell? = env("SHELL")?.let(Shell::fromProgram)

/**
 * Shells worth configuring on this machine: the login shell, plus any whose config file the user already keeps.
 *
 * A shell that is neither is left alone. Creating a startup file for a shell nobody runs is litter, and `--shell` says
 * so explicitly when it is wanted.
 */
fun detect(): List<Shell> {
    val home = home()
    val current = current()
    return Shell.ALL.filter { it == current || it.candidates(home).any(::isFile) }
}

/** Add the block to one shell's config file. [dryRun] computes the outcome and writes nothing. */
fun install(
    cfg: Config,
    shell: Shell,
    dryRun: Boolean,
): Change {
    val binDir = binDirStr(cfg)
    val file = shell.configFile(home())
    val text = read(file)
    val hadBlock = blockSpan(text) != null

    // A line the user wrote themselves already does the job. A second copy would be both redundant and impossible to
    // tell from theirs later.
    if (!hadBlock && mentions(text, binDir)) return Change(shell, file, Outcome.Unchanged)

    val next = splice(text, shell.block(binDir))
    if (next != null && !dryRun) write(file, next)
    val outcome =
        when {
            next == null -> Outcome.Unchanged
            hadBlock -> Outcome.Updated
            else -> Outcome.Added
        }
    return Change(shell, file, outcome)
}

/** Take the block back out of one shell's config file, leaving everything the user wrote exactly as it was. */
fun uninstall(
    shell: Shell,
    dryRun: Boolean,
): Change {
    val file = shell.configFile(home())
    val next = unsplice(read(file)) ?: return Change(shell, file, Outcome.Unchanged)
    if (!dryRun) write(file, next)
    return Change(shell, file, Outcome.Removed)
}

/**
 * Every shell startup file holding a ketch block, across all three shells and every file each of them reads.
 *
 * Deliberately narrower than [configuredIn]: that one answers "is the bin dir on PATH", a line the user wrote by hand
 * included. This one finds only blocks ketch wrote, because those are the only ones it may take back out.
 */
fun filesWithBlock(): List<Path> {
    val home = homeDir() ?: return emptyList()
    return Shell.ALL.flatMap { it.candidates(home) }.filter { readable(it)?.let(::blockSpan) != null }
}

/**
 * Take the ketch block out of one file named by path. True when it changed.
 *
 * The by-path entry point, for `ketch self uninstall`: it removes every block it can find rather than the file one
 * named shell happens to read now, since a shell the user has since stopped using still has ketch in its startup.
 */
fun uninstallFile(file: Path): Boolean {
    val next = unsplice(read(file)) ?: return false
    write(file, next)
    return true
}

/**
 * Config files that already put the bin dir on PATH, whether ketch wrote them or the user did.
 *
 * An unreadable file is not configured as far as anyone can tell, so it is skipped rather than reported: this feeds a
 * diagnostic, not a decision.
 */
fun configuredIn(cfg: Config): List<Path> {
    val home = homeDir()
    val binDir = runCatching { binDirStr(cfg) }.getOrNull()
    if (home == null || binDir == null) return emptyList()
    return Shell.ALL.flatMap { it.candidates(home) }.filter { readable(it)?.let { t -> mentions(t, binDir) } == true }
}

/**
 * The PATH line of `ketch doctor`.
 *
 * Three states, not two. A bin dir that is written into `.zshrc` but missing from this process's environment is not
 * broken (the shell that started ketch simply predates the edit), and calling that a failure sends the user round the
 * same loop forever.
 */
fun pathCheck(cfg: Config): DoctorCheck {
    val bin = cfg.binDir.toString()
    val configured = configuredIn(cfg)
    val user = userPathConfigured(cfg)
    val places = configured.map { it.toString() } + listOfNotNull("the user PATH".takeIf { user })
    return when {
        cfg.binDirOnPath() -> {
            DoctorCheck.ok("PATH", "$bin is on PATH")
        }

        places.isEmpty() -> {
            DoctorCheck.fail("PATH", "$bin is not on PATH", "Run `ketch path install`, or `ketch doctor --fix`.")
        }

        else -> {
            DoctorCheck.warn("PATH", "$bin is set up in ${places.joinToString(", ")} but not in this shell", "Open a new shell.")
        }
    }
}

/** Where one shell stands with ketch's PATH block. */
enum class ShellState {
    /** Its startup file has the block for this bin dir. */
    Configured,

    /** It is in use here, and its startup file has no block yet. */
    NotSetUp,

    /** Nothing says it is used on this machine. */
    NotInUse,
}

/** One shell's row in [Status]. */
data class ShellStatus(
    val shell: Shell,
    val state: ShellState,
    /** The startup file [install] would edit. */
    val file: Path,
)

/** Everything `ketch path` reports: whether the bin dir is on PATH now, and where it is or could be set up. */
data class Status(
    val onPath: Boolean,
    /** What the doctor's PATH check says about it. */
    val detail: String,
    /** Whether the Windows user PATH names the bin dir; null off Windows, where there is no user environment to set. */
    val userPath: Boolean?,
    /** Every shell ketch can set up, in [Shell.ALL] order. */
    val shells: List<ShellStatus>,
)

/** The PATH setup as `ketch path` shows it. Reads startup files; writes nothing. */
fun status(cfg: Config): Status {
    val home = home()
    val detected = runCatching { detect() }.getOrDefault(emptyList())
    val configured = configuredIn(cfg)
    val shells =
        Shell.ALL.map { shell ->
            val file = shell.configFile(home)
            val state =
                when {
                    file in configured -> ShellState.Configured
                    shell in detected -> ShellState.NotSetUp
                    else -> ShellState.NotInUse
                }
            ShellStatus(shell, state, file)
        }
    return Status(cfg.binDirOnPath(), pathCheck(cfg).detail, userPathOrNull(cfg), shells)
}

/** What one PATH setup step changed: a shell's startup file, or the Windows user PATH. */
sealed interface Setup {
    data class ShellFile(
        val change: Change,
    ) : Setup

    data class UserPath(
        val outcome: Outcome,
    ) : Setup
}

/** The shells in use here, or an error saying how to choose one when none can be told apart. */
fun detected(): List<Shell> {
    val detected = detect()
    // Guessing here would edit a startup file the user's shell never reads, and they would have no reason to look.
    if (detected.isEmpty()) {
        throw KetchError.Msg(
            "could not tell which shell you use (SHELL=${env("SHELL") ?: "unset"}). " +
                "Pass --shell bash|zsh|fish, or --all, or `ketch path install --print` for the line to add by hand.",
        )
    }
    return detected
}

/** `ketch path install` with no shell named: the startup file of every shell in use. */
fun installHere(
    cfg: Config,
    dryRun: Boolean,
): List<Setup> = detected().map { Setup.ShellFile(install(cfg, it, dryRun)) }

/** The line to add by hand, for a shell ketch does not know. */
fun manualLine(cfg: Config): String = Shell.Bash.export(binDirStr(cfg))

private fun home(): Path = homeDir() ?: throw KetchError.Msg("no home directory; set HOME")

/**
 * The bin dir as something a shell file can hold. A newline is one no amount of quoting fixes, so it is refused rather
 * than written and hoped for.
 */
private fun binDirStr(cfg: Config): String {
    val text = cfg.binDir.toString()
    if ('\n' in text) throw KetchError.Msg("$text contains a newline; no shell can express that on one line")
    return text
}

/** A missing config file reads as empty: it is about to be created. */
internal fun read(file: Path): String =
    try {
        textOrEmpty(file)
    } catch (e: FsException) {
        throw KetchError.fsKit(file, e)
    }

/** Rust `fs::read_to_string(..).ok()`: the text, or null for a missing, unreadable or non-UTF-8 file. */
private fun readable(file: Path): String? =
    try {
        if (isFile(file)) readText(file) else null
    } catch (_: FsException) {
        null
    }

/** Replace the file's contents, atomically and in place. */
internal fun write(
    file: Path,
    text: String,
) {
    // A startup file is very often a symlink into a dotfiles repository. Renaming over the link would replace it with
    // a regular file and quietly detach the user from their own dotfiles, so the write follows it first.
    val target = canon(file)
    try {
        WriteOptions(followSymlinks = false, createParent = true).write(target, text.encodeToByteArray())
    } catch (e: FsException) {
        throw KetchError.fsKit(target, e)
    }
}
