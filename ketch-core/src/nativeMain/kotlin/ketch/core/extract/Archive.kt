// Portable archive formats.
//
// Detection is by magic bytes, never by extension. Every entry path goes through `safeMemberPath` before it is joined
// onto the destination.
package ketch.core.extract

import archive.TarEntry
import archive.TarReader
import archive.TarType
import archive.ZipEntry
import archive.ZipReader
import archive.bzip2Source
import archive.gzipSource
import archive.xzSource
import fs.EntryKind
import fs.copyFile
import fs.entryKind
import fs.joinPath
import fs.removeFile
import fs.setTimes
import ketch.core.error.KetchError
import ketch.core.error.ioAt
import ketch.core.platform.ensureExecutable
import ketch.core.text.rustTrim
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Gzip header. The local source classifies from these same bytes. */
internal val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())

/** XZ header. The local source classifies from these same bytes. */
internal val XZ_MAGIC = byteArrayOf(0xfd.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)

/** bzip2 header. The local source classifies from these same bytes. */
internal val BZ2_MAGIC = "BZh".encodeToByteArray()

/** ZIP local-file header. The local source classifies from these same bytes. */
internal val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

private val USTAR = "ustar".encodeToByteArray()

/** Where tar writes its `ustar` marker in every header block. */
private const val USTAR_AT = 257

/**
 * Decoder memory ceiling for one XZ stream.
 *
 * Mostly the LZMA2 dictionary, so 256 MiB covers stock `xz -9` (64 MiB) and refuses a multi-gigabyte dictionary
 * before it is allocated. The plaintext itself is not buffered: the decoder yields it as it is decoded.
 */
private const val XZ_MEM_LIMIT = 256L * 1024 * 1024

private val EXECUTABLE = "755".toInt(8)
private val FILE_TYPE = "170000".toInt(8)
private val SYMLINK_TYPE = "120000".toInt(8)

/** The longest symlink target a zip member may carry; the body is read into memory, and no real path is longer. */
private const val MAX_ZIP_LINK_TARGET = 4096

/** What separates `tool.tar.gz` from `tool.gz` when the publisher named the file wrongly, which happens often enough to matter. */
internal fun looksLikeTar(head: ByteArray) = head.size >= USTAR_AT + USTAR.size && head.startsWith(USTAR, at = USTAR_AT)

private fun ByteArray.startsWith(
    prefix: ByteArray,
    at: Int = 0,
) = size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

internal fun xzReader(
    compressed: RawSource,
    memLimit: Long = XZ_MEM_LIMIT,
) = xzSource(compressed, memLimit)

/** Decode just enough of [path] to see what the stream holds; whatever fails along the way leaves what was read. */
private fun innerHead(
    path: Path,
    decode: (RawSource) -> RawSource,
): ByteArray {
    val file =
        try {
            SystemFileSystem.source(path)
        } catch (_: IOException) {
            return ByteArray(0)
        }
    val head = Buffer()
    try {
        decode(file).use { fill(it, head, HEAD_LEN) }
    } catch (_: IOException) {
        // A stream that breaks inside its first block says what it is no better than one that ends there.
    }
    return head.readByteArray()
}

/** Open [src] and hand [body] its decoded bytes. */
private fun decoded(
    src: Path,
    decode: (RawSource) -> RawSource,
    body: (RawSource) -> Unit,
) {
    val file = ioAt(src) { SystemFileSystem.source(src) }
    decode(file).use(body)
}

private class CompressedTar(
    override val id: String,
    private val magic: ByteArray,
    private val decode: (RawSource) -> RawSource,
) : Extractor {
    override fun detect(
        path: Path,
        head: ByteArray,
    ) = head.startsWith(magic) && looksLikeTar(innerHead(path, decode))

    override fun extract(
        src: Path,
        dest: Path,
    ) = decoded(src, decode) { unpackTar(it, dest, Budget.forArchive(src)) }
}

/** A compressed stream wrapping a single file rather than a tar stream. */
private class CompressedFile(
    override val id: String,
    private val magic: ByteArray,
    private val suffix: String,
    private val decode: (RawSource) -> RawSource,
) : Extractor {
    override fun detect(
        path: Path,
        head: ByteArray,
    ) = head.startsWith(magic) && !looksLikeTar(innerHead(path, decode))

    override fun extract(
        src: Path,
        dest: Path,
    ) = decoded(src, decode) { extractLoneFile(it, src, dest, suffix, Budget.forArchive(src)) }
}

/** `.tar.gz` / `.tgz` */
val TarGzExtractor: Extractor = CompressedTar("tar.gz", GZIP_MAGIC, ::gzipSource)

/** `.tar.xz` / `.txz` */
val TarXzExtractor: Extractor = CompressedTar("tar.xz", XZ_MAGIC) { xzReader(it) }

/** `.tar.bz2` */
val TarBz2Extractor: Extractor = CompressedTar("tar.bz2", BZ2_MAGIC, ::bzip2Source)

/** `.gz` wrapping a single file rather than a tar stream. */
val GzFileExtractor: Extractor = CompressedFile("gz", GZIP_MAGIC, ".gz", ::gzipSource)

/** `.xz` wrapping a single file rather than a tar stream. */
val XzFileExtractor: Extractor = CompressedFile("xz", XZ_MAGIC, ".xz") { xzReader(it) }

/** `.bz2` wrapping a single file rather than a tar stream. */
val Bz2FileExtractor: Extractor = CompressedFile("bz2", BZ2_MAGIC, ".bz2", ::bzip2Source)

/** Uncompressed `.tar` */
object TarExtractor : Extractor {
    override val id = "tar"

    override fun detect(
        path: Path,
        head: ByteArray,
    ) = looksLikeTar(head)

    override fun extract(
        src: Path,
        dest: Path,
    ) = decoded(src, { it }) { unpackTar(it, dest, Budget.forArchive(src)) }
}

/** `.zip` */
object ZipExtractor : Extractor {
    override val id = "zip"

    override fun detect(
        path: Path,
        head: ByteArray,
    ) = head.startsWith(ZIP_MAGIC)

    override fun extract(
        src: Path,
        dest: Path,
    ) = unpackZip(src, dest, Budget.forArchive(src))
}

/** A bare executable published with no container at all. Must be last in the extractor list: it accepts anything the others refused. */
object RawBinaryExtractor : Extractor {
    override val id = "raw"

    // An archive whose codec is missing is not a bare program: copied as one, it would install as an executable
    // nobody can run. Refused here, it reads as an unsupported format.
    override fun detect(
        path: Path,
        head: ByteArray,
    ) = !isContainerHead(head)

    override fun extract(
        src: Path,
        dest: Path,
    ) {
        val out = joinPath(dest, safeMemberPath(src.name.ifEmpty { "payload" }))
        ioAt(out) { copyFile(src, out) }
        setUnixMode(out, EXECUTABLE)
    }
}

/** True when the first bytes open a compressed stream or a zip. Every one of them is claimed by its codec's extractor. */
internal fun isContainerHead(head: ByteArray) = listOf(GZIP_MAGIC, XZ_MAGIC, BZ2_MAGIC, ZIP_MAGIC).any { head.startsWith(it) }

/** Every format this build unpacks on any OS, most specific first. [RawBinaryExtractor] accepts what the others refused, so it stays last. */
fun portableExtractors(): List<Extractor> =
    listOf(
        TarGzExtractor,
        TarXzExtractor,
        TarBz2Extractor,
        TarExtractor,
        ZipExtractor,
        GzFileExtractor,
        XzFileExtractor,
        Bz2FileExtractor,
        RawBinaryExtractor,
    )

// `#!`, then DOS/Windows PE: the next two bytes are the PE offset, and the MZ magic is enough to tell a program from
// a text file published with the same name.
private val PROGRAM_PREFIXES = listOf("#!".encodeToByteArray(), "MZ".encodeToByteArray(), "\u007fELF".encodeToByteArray())

/** Mach-O 32 and 64 bit in both byte orders, plus the fat (universal) wrappers, read big-endian. */
private val MACHO_MAGICS = setOf(0xfeedfaceu, 0xcefaedfeu, 0xfeedfacfu, 0xcffaedfeu, 0xcafebabeu, 0xbebafecau)

private const val MAGIC_LEN = 4

/** True when the first bytes are a Mach-O image, an ELF image, a PE image or a `#!` line: "a program" rather than "a README that happens to be marked +x". */
fun isProgramHead(head: ByteArray) =
    PROGRAM_PREFIXES.any { head.startsWith(it) } ||
        (head.size >= MAGIC_LEN && head.take(MAGIC_LEN).fold(0u) { acc, b -> (acc shl Byte.SIZE_BITS) or b.toUByte().toUInt() } in MACHO_MAGICS)

/**
 * Unpack a tar stream, validating every member path and link target.
 *
 * Written out rather than left to a library's unpack so the traversal guard is ours and applies identically to files,
 * directories and links. Symlinks and hard links are deferred until every regular member is on disk: copy
 * materialisation needs the target file, and tar and zip often list the link before the file it points at.
 */
internal fun unpackTar(
    source: RawSource,
    dest: Path,
    budget: Budget,
) {
    val unpack = TarUnpack(dest, budget)
    TarReader(source).use { tar ->
        // Extended headers carry metadata, not payload, and have no real path.
        val members = generateSequence { ioAt(dest) { tar.next() } }.filter { it.type !in METADATA }
        for (entry in members) unpack.member(tar, entry)
    }
    finishDeferredLinks(dest, unpack.hardlinks, unpack.symlinks)
    applyDirModes(unpack.dirModes)
}

private val METADATA = setOf(TarType.PaxGlobal, TarType.GnuLongName)

private class TarUnpack(
    private val dest: Path,
    private val budget: Budget,
) {
    val symlinks = mutableListOf<Pair<Path, String>>()
    val hardlinks = mutableListOf<Pair<Path, String>>()
    val dirModes = mutableListOf<Pair<Path, Int>>()

    fun member(
        tar: TarReader,
        entry: TarEntry,
    ) {
        val safe = safeMemberPath(entry.path)
        val out = joinPath(dest, safe)
        budget.entry()
        when (entry.type) {
            TarType.Directory -> {
                // Created here, its mode set only once everything is written: a 0555 directory listed before its
                // children would refuse them, and one listed after would refuse removal later.
                walkInside(dest, safe, create = true)
                entry.mode?.let { dirModes += out to it }
            }

            TarType.Symlink -> {
                val target = entry.linkName ?: throw KetchError.Msg("archive symlink ${entry.path} has no target")
                checkLinkTarget(safe, target)
                ensureParent(dest, safe)
                symlinks += out to target
            }

            TarType.Link -> {
                // A tar hard link names its target from the archive root; the link waits until that member is
                // unpacked.
                val target = entry.linkName ?: throw KetchError.Msg("archive hard link ${entry.path} has no target")
                val safeTarget = safeMemberPath(target)
                ensureParent(dest, safe)
                hardlinks += out to safeTarget
            }

            TarType.Regular, TarType.Continuous, TarType.GnuSparse -> {
                // The size is the unpacked size, sparse holes included, so it is charged before a byte reaches the
                // disk.
                budget.bytes(entry.size)
                ensureParent(dest, safe)
                unpackMember(tar, entry, out)
            }

            // Character and block devices and fifos have no place in a release.
            else -> {}
        }
    }

    /** What tar-rs `Entry::unpack` does: replace what is there, keep the modification time, then the permission bits. */
    private fun unpackMember(
        tar: TarReader,
        entry: TarEntry,
        out: Path,
    ) {
        val existing = entryKind(out)
        if (existing != null && existing != EntryKind.Dir) ioAt(out) { removeFile(out) }
        writeFile(tar.body(), out, null)
        entry.mtime?.let { mtime -> ioAt(out) { setTimes(out, if (mtime == 0L) 1 else mtime) } }
        entry.mode?.let { setUnixMode(out, payloadMode(it)) }
    }
}

internal fun unpackZip(
    src: Path,
    dest: Path,
    budget: Budget,
) {
    val zip = parsed(src) { ZipReader(src) }
    zip.use {
        val symlinks = mutableListOf<Pair<Path, String>>()
        for (entry in zip.entries) parsed(src) { zip.open(entry) }.use { zipMember(entry, it, dest, budget, symlinks) }
        finishDeferredLinks(dest, emptyList(), symlinks)
    }
}

/** A zip that cannot be read as one is unparseable, however the read failed (Rust maps every `ZipError` so). */
private inline fun <T> parsed(
    src: Path,
    body: () -> T,
): T =
    try {
        body()
    } catch (e: IOException) {
        throw KetchError.Parse(src.toString(), e.message.orEmpty(), e)
    }

private fun zipMember(
    entry: ZipEntry,
    body: RawSource,
    dest: Path,
    budget: Budget,
    symlinks: MutableList<Pair<Path, String>>,
) {
    val name = entry.name
    // Finder metadata, not payload.
    if (name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store")) return
    val safe = safeMemberPath(name)
    val out = joinPath(dest, safe)
    budget.entry()
    if (entry.isDir) return walkInside(dest, safe, create = true)
    val mode = entry.unixMode
    ensureParent(dest, safe)
    // Zip stores a symlink as a regular member whose body is the target.
    if (mode != null && mode and FILE_TYPE == SYMLINK_TYPE) {
        val target = zipLinkTarget(body, safe, out)
        checkLinkTarget(safe, target)
        symlinks += out to target
    } else {
        writeFile(body, out, budget)
        // Without this, every binary in a zip lands non-executable.
        mode?.let { setUnixMode(out, payloadMode(it)) }
        // A zip written on Windows — Java's `ZipOutputStream`, .NET, Gradle distributions — marks every member 0644
        // or carries no unix mode at all. Placement only links files it can run, so from the outside that payload
        // looks like it contains nothing. The bytes are the evidence left: a program header says the file is one.
        if (isProgramHead(headOrEmpty(out))) ensureExecutable(out)
    }
}

private fun headOrEmpty(path: Path) =
    try {
        readHead(path)
    } catch (_: KetchError.Io) {
        ByteArray(0)
    }

private fun zipLinkTarget(
    body: RawSource,
    safe: String,
    out: Path,
): String {
    val bytes = ioAt(out) { readUpTo(body, MAX_ZIP_LINK_TARGET + 1) }
    if (bytes.size > MAX_ZIP_LINK_TARGET) throw KetchError.Msg("refusing archive link $safe with an over-long target")
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw KetchError.Io(out, IOException("stream did not contain valid UTF-8", e))
        }
    return text.rustTrim()
}

/** Write the one file a compressed stream holds, named after [src] without [suffix]. */
internal fun extractLoneFile(
    decoder: RawSource,
    src: Path,
    dest: Path,
    suffix: String,
    budget: Budget,
) {
    val stem = src.name.ifEmpty { "payload" }.removeSuffix(suffix)
    val out = joinPath(dest, safeMemberPath(stem))
    budget.entry()
    writeFile(decoder, out, budget)
    setUnixMode(out, EXECUTABLE)
}
