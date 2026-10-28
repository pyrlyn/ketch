@file:OptIn(ExperimentalForeignApi::class)

package ketch.core.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.free
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.getpwuid
import platform.posix.getuid
import platform.posix.strlen

internal actual fun envBytes(key: String): ByteArray? = getenv(key)?.let { it.readBytes(strlen(it).toInt()) }

/** `HOME` when set, else the user's entry in the password database. */
internal actual fun homeDir(): Path? {
    envOs("HOME")?.takeIf { it.isNotEmpty() }?.let { return Path(it) }
    return getpwuid(getuid())
        ?.pointed
        ?.pw_dir
        ?.toKString()
        ?.takeIf { it.isNotEmpty() }
        ?.let { Path(it) }
}

actual fun currentDir(): Path? {
    val buf = getcwd(null, 0u) ?: return null
    try {
        return Path(buf.toKString())
    } finally {
        free(buf)
    }
}
