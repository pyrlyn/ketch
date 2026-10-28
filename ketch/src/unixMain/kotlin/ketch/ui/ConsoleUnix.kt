@file:OptIn(ExperimentalForeignApi::class)

package ketch.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.TIOCGWINSZ
import platform.posix.ioctl
import platform.posix.isatty
import platform.posix.winsize

internal actual fun isTerminal(fd: Int) = isatty(fd) == 1

internal actual fun terminalColumns(fd: Int): Int? =
    memScoped {
        val size = alloc<winsize>()
        if (isTerminal(fd) && ioctl(fd, TIOCGWINSZ.convert(), size.ptr) == 0 && size.ws_col.toInt() > 0) size.ws_col.toInt() else null
    }

// Every Unix terminal takes escape sequences as they are.
internal actual fun terminalTakesAnsi(fd: Int) = true

// A Unix terminal takes the bytes as they are written.
actual fun prepareConsole() = Unit
