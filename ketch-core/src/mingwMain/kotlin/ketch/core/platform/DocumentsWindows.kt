@file:OptIn(ExperimentalForeignApi::class)

package ketch.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.io.files.Path
import platform.windows.CSIDL_LOCAL_APPDATA
import platform.windows.CSIDL_PERSONAL
import platform.windows.MAX_PATH
import platform.windows.SHGetFolderPathW
import platform.windows.S_OK
import platform.windows.WCHARVar

/** The shell's Documents folder (Rust: `dirs::document_dir`), wherever OneDrive or policy moved it. */
internal actual fun documentsDir(): Path? = shellFolder(CSIDL_PERSONAL)

/** `%LOCALAPPDATA%` as the shell knows it, so a variable someone changed does not move it. */
internal actual fun localDataDir(): Path? = shellFolder(CSIDL_LOCAL_APPDATA)

private fun shellFolder(csidl: Int): Path? =
    memScoped {
        val buf = allocArray<WCHARVar>(MAX_PATH)
        if (SHGetFolderPathW(null, csidl, null, 0u, buf) != S_OK) return null
        buf.toKStringFromUtf16().takeIf { it.isNotEmpty() }?.let(::Path)
    }
