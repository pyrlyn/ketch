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

/** The variable's bytes, as Rust `var_os` sees them; null when unset. */
private fun envBytes(key: String): ByteArray? = getenv(key)?.let { it.readBytes(strlen(it).toInt()) }

/** Rust `env::var(key).ok()`: null when unset or not UTF-8. */
internal fun env(key: String): String? = envBytes(key)?.let { decodeStrict(it) }

/** Rust `env::var_os`, lossily as a path takes it. */
internal fun envOs(key: String): String? = envBytes(key)?.decodeToString()

/** Rust `env::var`'s three outcomes, for the one caller that tells "not UTF-8" from "unset". */
internal sealed interface EnvVar {
    data object Unset : EnvVar

    data object NotUnicode : EnvVar

    data class Set(
        val value: String,
    ) : EnvVar
}

internal fun envVar(key: String): EnvVar {
    val bytes = envBytes(key) ?: return EnvVar.Unset
    return decodeStrict(bytes)?.let { EnvVar.Set(it) } ?: EnvVar.NotUnicode
}

private fun decodeStrict(bytes: ByteArray): String? =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

/** Rust `dirs::home_dir`: `HOME` when set, else the user's entry in the password database. */
internal fun homeDir(): Path? {
    envOs("HOME")?.takeIf { it.isNotEmpty() }?.let { return Path(it) }
    return getpwuid(getuid())
        ?.pointed
        ?.pw_dir
        ?.toKString()
        ?.takeIf { it.isNotEmpty() }
        ?.let { Path(it) }
}

/** Rust `env::current_dir`; null when the directory is gone. */
internal fun currentDir(): Path? {
    val buf = getcwd(null, 0u) ?: return null
    try {
        return Path(buf.toKString())
    } finally {
        free(buf)
    }
}
