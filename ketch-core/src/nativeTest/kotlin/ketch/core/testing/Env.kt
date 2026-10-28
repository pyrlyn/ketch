package ketch.core.testing

import ketch.core.config.envOs
import process.setEnv
import process.unsetEnv

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
    val saved = touched.associateWith { envOs(it) }
    try {
        keys.forEach { unsetEnv(it) }
        set.forEach { (k, v) -> setEnv(k, v) }
        return body()
    } finally {
        saved.forEach { (k, v) -> if (v == null) unsetEnv(k) else setEnv(k, v) }
    }
}
