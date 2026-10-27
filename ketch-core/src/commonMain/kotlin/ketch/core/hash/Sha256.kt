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

private const val SHA256_HEX = 64

/** True for 64 hex digits of either case, the only shape a SHA-256 written as text takes. */
fun isSha256Hex(text: String) = text.length == SHA256_HEX && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
