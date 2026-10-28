package ketch.core.config

import kotlinx.io.files.Path

/** The variable's bytes, as Rust `var_os` sees them; null when unset. UTF-8 off Windows by convention, WTF-8 on it. */
internal expect fun envBytes(key: String): ByteArray?

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

/** Rust `dirs::home_dir`. */
internal expect fun homeDir(): Path?

/** Rust `env::current_dir`; null when the directory is gone. */
expect fun currentDir(): Path?
