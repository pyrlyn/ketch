@file:OptIn(ExperimentalForeignApi::class)

package ketch.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix._O_BINARY
import platform.posix._setmode
import platform.windows.CONSOLE_SCREEN_BUFFER_INFO
import platform.windows.CP_UTF8
import platform.windows.DWORDVar
import platform.windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING
import platform.windows.GetConsoleMode
import platform.windows.GetConsoleScreenBufferInfo
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetConsoleCP
import platform.windows.SetConsoleMode
import platform.windows.SetConsoleOutputCP

private fun handle(fd: Int): HANDLE? =
    GetStdHandle(
        when (fd) {
            0 -> STD_INPUT_HANDLE
            1 -> STD_OUTPUT_HANDLE
            else -> STD_ERROR_HANDLE
        },
    )

// A console is what answers GetConsoleMode, as in Rust's std: `_isatty` also says yes to NUL and other character
// devices, and a prompt would then wait on a NUL that never answers.
internal actual fun isTerminal(fd: Int): Boolean =
    memScoped {
        val mode = alloc<DWORDVar>()
        GetConsoleMode(handle(fd), mode.ptr) != 0
    }

internal actual fun terminalColumns(fd: Int): Int? =
    memScoped {
        val info = alloc<CONSOLE_SCREEN_BUFFER_INFO>()
        if (GetConsoleScreenBufferInfo(handle(fd), info.ptr) == 0) return null
        (info.srWindow.Right - info.srWindow.Left + 1).takeIf { it > 0 }
    }

internal actual fun terminalTakesAnsi(fd: Int): Boolean =
    memScoped {
        val h = handle(fd)
        val mode = alloc<DWORDVar>()
        if (GetConsoleMode(h, mode.ptr) == 0) return false
        val vt = ENABLE_VIRTUAL_TERMINAL_PROCESSING.toUInt()
        // Windows Terminal and recent consoles have it on already, and switching it on again is harmless.
        mode.value and vt != 0u || SetConsoleMode(h, mode.value or vt) != 0
    }

// Kotlin writes UTF-8 bytes, where Rust's std hands the console UTF-16; without this a console on an OEM code page
// shows a package description or a path with an accent as mojibake, and reads a typed answer the same way. Output goes
// out in binary mode: the C runtime's text mode turns every `\n` into `\r\n`, and Rust writes the bytes as they are,
// so a script or a snapshot reading ketch's output would see two different programs.
actual fun prepareConsole() {
    SetConsoleOutputCP(CP_UTF8.toUInt())
    SetConsoleCP(CP_UTF8.toUInt())
    _setmode(1, _O_BINARY)
    _setmode(2, _O_BINARY)
}
