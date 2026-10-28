package ketch.core.process

import process.Command
import process.runBounded
import kotlin.time.Duration.Companion.seconds

private val TASKKILL_TIMEOUT = 10.seconds
private const val TASKKILL_OUTPUT = 1L shl 16

internal actual fun askToExit(pid: Long) = taskkill(listOf("/PID", pid.toString()))

internal actual fun forceExit(pid: Long) = taskkill(listOf("/PID", pid.toString(), "/F"))

/** Best effort, like the Rust `let _ =`: a process that is already gone is what was wanted. */
private fun taskkill(args: List<String>) {
    runCatching { Command("taskkill", args).runBounded(TASKKILL_TIMEOUT, TASKKILL_OUTPUT) }
}
