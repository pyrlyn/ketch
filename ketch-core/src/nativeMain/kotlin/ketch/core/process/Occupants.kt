// Processes holding a file we are about to replace.
//
// `ketch upgrade` and `ketch self upgrade` ask before stopping those processes. A listing failure is treated as
// "nobody": replacement then proceeds as it does today.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.process

import fs.EntryKind
import fs.canon
import fs.entryKind
import fs.isDir
import ketch.core.config.HOST_WINDOWS
import ketch.core.extract.readHead
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.shell.windowsPathKey
import ketch.core.state.processAlive
import ketch.core.text.asciiLowercase
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.getpid
import platform.posix.usleep
import process.Command
import process.Output
import process.ProcessException
import process.runBounded
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long a listing subprocess may run before it is stopped. The listing is best-effort ("could not list" and
 * "nobody" are the same answer), so a stuck listing must not freeze the upgrade. Linux reads `/proc` and does not spawn
 * a listing process.
 */
internal val LIST_TIMEOUT = 30.seconds

/** What a listing may print; `lsof -t` prints one pid per line. */
private const val LIST_OUTPUT = 1L shl 20

/** How long a process asked to exit gets before it is killed. */
private val GRACE = 200.milliseconds

/**
 * Ask to stop processes using [paths]. `--yes` stops them without asking; a decline leaves them running and the
 * caller continues as before. The question is the decider's to answer: a front end that cannot ask declines.
 */
fun offerToStop(
    paths: List<Path>,
    yes: Boolean,
    cx: Ctx,
) {
    val report = cx.report
    val me = getpid().toLong()
    val seen = mutableSetOf<Long>()
    val occupants = using(paths, report).filter { it.pid != me && seen.add(it.pid) }
    if (occupants.isEmpty()) return
    for (occupant in occupants) report.step("in use", "pid ${occupant.pid} ${occupant.path}")
    if (yes || cx.decider.stopProcesses(occupants)) {
        for (occupant in occupants) {
            report.step("stopping", "pid ${occupant.pid}")
            terminate(occupant.pid)
        }
    }
}

/**
 * Processes running one of [paths]: as their executable, or as the script an interpreter's command line names. A
 * listing that had to be stopped is a warning on [report].
 */
fun using(
    paths: List<Path>,
    report: Report,
): List<Occupant> {
    val keys = uniqueKeys(paths)
    return if (keys.isEmpty()) emptyList() else list(keys, report)
}

/** The host's listing of who runs each `(path, key)`. */
internal expect fun list(
    keys: List<Pair<Path, String>>,
    report: Report,
): List<Occupant>

private fun uniqueKeys(paths: List<Path>): List<Pair<Path, String>> {
    val seen = mutableSetOf<String>()
    return paths.mapNotNull { path -> pathKey(path).takeIf { it.isNotEmpty() && seen.add(it) }?.let { path to it } }
}

/** Windows paths compare case-blind and slash-blind, so its key is folded the way a PATH entry's is. */
internal fun pathKey(p: Path): String = canon(p).let { if (HOST_WINDOWS) windowsPathKey(it) else it.toString() }

/** Sentence for a listing stopped at its deadline, naming the program and pid. */
fun stoppedDetail(
    program: String,
    pid: Long?,
    timeout: Duration,
): String =
    if (pid != null) {
        "$program (pid $pid) did not finish within ${timeout.inWholeSeconds}s and was stopped"
    } else {
        "$program did not start within ${timeout.inWholeSeconds}s; the wait was ended"
    }

/**
 * Run [command] to completion, or give up after [timeout].
 *
 * A listing is best-effort, so a child that does not finish is warned about, stopped, and reported as "no listing".
 */
internal fun outputBounded(
    command: Command,
    timeout: Duration,
    report: Report,
): Output? =
    try {
        command.runBounded(timeout, LIST_OUTPUT)
    } catch (e: ProcessException) {
        e.stoppedPid?.let { report.warn(stoppedDetail(command.program, it.toLong(), timeout)) }
        null
    }

internal fun matchesExe(
    exe: Path,
    candidate: Path,
    key: String,
): Boolean {
    val exeKey = pathKey(exe)
    if (exeKey == key) return true
    val isBundle = isDir(candidate) || candidate.name.substringAfterLast('.', "").equals("app", ignoreCase = true)
    return isBundle && (exeKey.startsWith("$key/") || exeKey.startsWith("$key\\"))
}

private val ELF = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

private val MACH_O =
    listOf(
        listOf(0xfe, 0xed, 0xfa, 0xce),
        listOf(0xfe, 0xed, 0xfa, 0xcf),
        listOf(0xce, 0xfa, 0xed, 0xfe),
        listOf(0xcf, 0xfa, 0xed, 0xfe),
        listOf(0xca, 0xfe, 0xba, 0xbe),
    ).map { magic -> ByteArray(magic.size) { magic[it].toByte() } }

private const val MAGIC_LEN = 4

/**
 * Whether [candidate] is a compiled program. The OS then runs it as the process's own executable, so the executable
 * (and on Linux an open file) is the evidence; a command line that merely names it — an editor, a shell — is not. A
 * script is run by an interpreter, and only its command line says which.
 */
internal fun isNative(candidate: Path): Boolean {
    val head = runCatching { readHead(candidate) }.getOrDefault(ByteArray(0)).take(MAGIC_LEN).toByteArray()
    return (head.size >= 2 && head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()) ||
        head.contentEquals(ELF) ||
        MACH_O.any { head.contentEquals(it) }
}

/**
 * Command-line evidence counts only for a script file, and only for an argument that is exactly its path:
 * `…/bin/rga` and `…/bin/rg-helper` are not `…/bin/rg`.
 */
private inline fun scriptNamed(
    candidate: Path,
    names: () -> Boolean,
) = entryKind(canon(candidate)) == EntryKind.File && !isNative(candidate) && names()

/**
 * Windows hands over one command line, quoted however the launcher chose — cmd wraps a script in two pairs of quotes —
 * so instead of splitting it, a match must be bounded by whitespace, a quote or either end.
 */
internal fun cmdHits(
    cmdline: String,
    candidate: Path,
    key: String,
): Boolean =
    scriptNamed(candidate) {
        // Windows CommandLine casing is arbitrary; keys from `pathKey` are folded.
        val folded = cmdline.asciiLowercase()
        val raw = candidate.toString().asciiLowercase().replace('/', '\\')
        wholeArgIn(folded, raw) || wholeArgIn(folded, key)
    }

internal fun wholeArgIn(
    cmdline: String,
    needle: String,
): Boolean {
    fun bound(c: Char?) = c == null || c.isWhitespace() || c == '"'
    // Non-overlapping, as Rust's `match_indices` finds them.
    val starts = generateSequence(cmdline.indexOf(needle).takeIf { it >= 0 }) { cmdline.indexOf(needle, it + needle.length).takeIf { i -> i >= 0 } }
    return needle.isNotEmpty() && starts.any { i -> bound(cmdline.getOrNull(i - 1)) && bound(cmdline.getOrNull(i + needle.length)) }
}

/** [args] is `/proc/<pid>/cmdline`: the arguments, each ended by a NUL. */
internal fun argsHit(
    args: String,
    candidate: Path,
    key: String,
): Boolean =
    scriptNamed(candidate) {
        val raw = candidate.toString()
        args.split('\u0000').any { it.isNotEmpty() && (it == raw || it == key) }
    }

/** Ask [pid] to exit, and kill it if it is still there a moment later. */
internal fun terminate(pid: Long) {
    askToExit(pid)
    usleep(GRACE.inWholeMicroseconds.convert())
    if (processAlive(pid)) forceExit(pid)
}

/** `SIGTERM`, or `taskkill` without `/F`, which asks the process's windows to close. */
internal expect fun askToExit(pid: Long)

/** `SIGKILL`, or `taskkill /F`. */
internal expect fun forceExit(pid: Long)
