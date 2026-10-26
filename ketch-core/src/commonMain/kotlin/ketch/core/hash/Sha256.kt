package ketch.core.hash

import org.kotlincrypto.hash.sha2.SHA256

/**
 * Streaming SHA-256 (Rust: `sha2::Sha256`). The only place that sees KotlinCrypto, so the library can be
 * replaced alone.
 */
class Sha256 {
    private val digest = SHA256()

    fun update(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Sha256 = apply { digest.update(bytes, offset, length) }

    /** Lower-case hex of the digest; the hasher starts over afterwards. */
    fun hex(): String = digest.digest().toHexString()

    companion object {
        fun hex(bytes: ByteArray): String = Sha256().update(bytes).hex()
    }
}
