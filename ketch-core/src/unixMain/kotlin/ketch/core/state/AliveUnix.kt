package ketch.core.state

import process.Command
import process.runBounded
import kotlin.time.Duration.Companion.seconds

private val PS_TIMEOUT = 10.seconds
private const val PS_OUTPUT = 4096L

/**
 * `ps` rather than `kill(pid, 0)`: signalling a process owned by another user fails with EPERM, which reading as
 * "gone" would turn into stealing a lock that is very much still held.
 */
actual fun processAlive(pid: Long): Boolean =
    runCatching { Command("/bin/ps", listOf("-p", pid.toString(), "-o", "pid=")).runBounded(PS_TIMEOUT, PS_OUTPUT) }
        .getOrNull()
        ?.status
        ?.success ?: true
