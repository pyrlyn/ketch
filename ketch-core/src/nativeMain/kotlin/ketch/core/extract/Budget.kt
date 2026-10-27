package ketch.core.extract

import fs.FsException
import ketch.core.error.KetchError
import ketch.core.error.io
import ketch.core.error.ioAt
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.ENOENT
import ketch.core.text.bytes as sizeText

/** How far a compressed archive may expand relative to its own size. Real release archives sit well below 20:1; a bomb sits at 1000:1 and above. */
private const val MAX_EXPANSION_RATIO = 100L

/** Every archive may unpack at least this much, so a small archive of highly compressible files (zero-padded firmware, sparse data) still works. */
private const val MIN_UNPACKED_BYTES = 256L * 1024 * 1024

/** No archive unpacks past this, whatever its size. */
private const val MAX_UNPACKED_BYTES = 32L * 1024 * 1024 * 1024

/** No archive writes more members than this. The largest toolchains ship tens of thousands of files; millions of empty members exist only to exhaust inodes. */
private const val MAX_ENTRIES = 250_000L

/** How much is copied at a time; each chunk is charged before it is written. */
private const val CHUNK = 64L * 1024

/**
 * What one extraction may still write: bytes and entries.
 *
 * Archive headers and compressed streams are written by someone else, so the size an archive expands to is untrusted
 * input. One budget per extraction, charged by every extractor, keeps a small bomb from filling the disk under the
 * ketch root.
 */
class Budget(
    internal val limitBytes: Long,
    private val limitEntries: Long,
) {
    private var bytesLeft = limitBytes
    private var entriesLeft = limitEntries

    /** Charge one member (file, directory or link). */
    fun entry() {
        if (entriesLeft == 0L) throw KetchError.Msg("refusing archive with more than $limitEntries entries")
        entriesLeft--
    }

    /** Charge [n] bytes about to be written. */
    fun bytes(n: Long) {
        if (n > bytesLeft) throw tooLarge()
        bytesLeft -= n
    }

    private fun tooLarge() = KetchError.Msg("refusing archive that unpacks to more than ${sizeText(limitBytes)}")

    /** Write [source] to [out], failing before a chunk that would overrun the budget reaches the disk (Rust `BudgetWriter`). */
    fun write(
        source: RawSource,
        out: Path,
    ) = writeFile(source, out, this)

    internal fun written(
        out: Path,
        n: Long,
    ) {
        if (n > bytesLeft) throw KetchError.Io(out, IOException(tooLarge().message))
        bytesLeft -= n
    }

    companion object {
        /** The budget for an archive of [compressed] bytes: a ratio of its size, never below the floor and never above the ceiling. */
        fun forCompressedLen(compressed: Long): Budget {
            val scaled = if (compressed > Long.MAX_VALUE / MAX_EXPANSION_RATIO) Long.MAX_VALUE else compressed * MAX_EXPANSION_RATIO
            return Budget(scaled.coerceIn(MIN_UNPACKED_BYTES, MAX_UNPACKED_BYTES), MAX_ENTRIES)
        }

        /** The budget for the archive at [src]. */
        fun forArchive(src: Path): Budget {
            val meta = ioAt(src) { SystemFileSystem.metadataOrNull(src) ?: throw FsException.ofErrno(null, ENOENT) }
            return forCompressedLen(meta.size)
        }
    }
}

/** Write [source] to [out], charging [budget] for each chunk before it lands when there is one. */
internal fun writeFile(
    source: RawSource,
    out: Path,
    budget: Budget?,
) {
    try {
        SystemFileSystem.sink(out).use { sink ->
            val chunk = Buffer()
            while (true) {
                val n = source.readAtMostTo(chunk, CHUNK)
                if (n == -1L) break
                budget?.written(out, n)
                sink.write(chunk, n)
            }
        }
    } catch (e: IOException) {
        throw KetchError.io(out, e)
    }
}
