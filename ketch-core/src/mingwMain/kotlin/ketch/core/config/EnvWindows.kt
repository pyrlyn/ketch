@file:OptIn(ExperimentalForeignApi::class)

package ketch.core.config

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.wcstr
import kotlinx.io.files.Path
import platform.posix._wgetcwd
import platform.posix._wgetenv
import platform.posix.free

/**
 * The variable as UTF-16 from the CRT, which tells unset from empty, as UTF-8 bytes; an unpaired surrogate becomes a
 * byte no UTF-8 decoder accepts, so `env` sees "not Unicode" and `envOs` a replacement character, as Rust does.
 */
internal actual fun envBytes(key: String): ByteArray? = _wgetenv(key.wcstr)?.toKStringFromUtf16()?.let(::wtf8)

/**
 * The profile folder, as `USERPROFILE` names it. `HOME` is not read: Rust's `dirs` ignores it on Windows, and Git Bash
 * sets it to a path of its own. `dirs` asks the shell for the profile folder, which Windows also puts in
 * `USERPROFILE`; reading the variable keeps tests able to point it elsewhere.
 */
internal actual fun homeDir(): Path? = envOs("USERPROFILE")?.takeIf { it.isNotEmpty() }?.let(::Path)

actual fun currentDir(): Path? {
    val buf = _wgetcwd(null, 0) ?: return null
    try {
        return Path(buf.toKStringFromUtf16())
    } finally {
        free(buf)
    }
}

private const val NOT_UTF8 = 0xFF

private fun wtf8(text: String): ByteArray {
    if (text.indices.none { lone(text, it) }) return text.encodeToByteArray()
    val out = mutableListOf<Byte>()
    var start = 0
    for (i in text.indices) {
        if (!lone(text, i)) continue
        out += text.substring(start, i).encodeToByteArray().asList()
        out += NOT_UTF8.toByte()
        start = i + 1
    }
    out += text.substring(start).encodeToByteArray().asList()
    return out.toByteArray()
}

/** Whether the char at [i] is half of a surrogate pair with no other half. */
private fun lone(
    text: String,
    i: Int,
): Boolean {
    val c = text[i]
    return when {
        c.isHighSurrogate() -> text.getOrNull(i + 1)?.isLowSurrogate() != true
        c.isLowSurrogate() -> text.getOrNull(i - 1)?.isHighSurrogate() != true
        else -> false
    }
}
