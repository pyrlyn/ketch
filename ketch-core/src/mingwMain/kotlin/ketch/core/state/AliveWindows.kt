package ketch.core.state

import process.Command
import process.runBounded
import kotlin.time.Duration.Companion.seconds

private val TASKLIST_TIMEOUT = 10.seconds
private const val TASKLIST_OUTPUT = 1L shl 16

/**
 * `tasklist` rather than OpenProcess, as Rust does: a process of another user may refuse to be opened, and that must
 * not read as gone. It prints "INFO: No tasks…" for a pid that is not running, so the pid in its output is the answer.
 */
actual fun processAlive(pid: Long): Boolean {
    val out =
        runCatching { Command("tasklist", listOf("/FI", "PID eq $pid", "/NH")).runBounded(TASKLIST_TIMEOUT, TASKLIST_OUTPUT) }
            .getOrNull() ?: return true
    return out.status.success && pid.toString() in out.stdout.decodeToString()
}
