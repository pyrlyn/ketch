@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.testing

import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * Rust's `CleanEnv`: [keys] are cleared, [set] applied, and every variable either touches is put back afterwards.
 * Native tests run on one thread, so no lock is needed around it.
 */
fun <T> withEnv(
    keys: List<String>,
    set: Map<String, String> = emptyMap(),
    body: () -> T,
): T {
    val touched = keys + set.keys
    val saved = touched.associateWith { getenv(it)?.toKString() }
    try {
        keys.forEach { unsetenv(it) }
        set.forEach { (k, v) -> setEnv(k, v) }
        return body()
    } finally {
        saved.forEach { (k, v) -> if (v == null) unsetenv(k) else setEnv(k, v) }
    }
}

fun setEnv(
    key: String,
    value: String,
) {
    check(setenv(key, value, 1) == 0) { "setenv $key" }
}
