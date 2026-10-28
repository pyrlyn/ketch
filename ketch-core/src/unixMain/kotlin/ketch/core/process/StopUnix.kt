package ketch.core.process

import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.kill

internal actual fun askToExit(pid: Long) {
    kill(pid.toInt(), SIGTERM)
}

internal actual fun forceExit(pid: Long) {
    kill(pid.toInt(), SIGKILL)
}
