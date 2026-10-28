package ketch.core.extract

import archive.ArchiveException
import archive.TarWriter
import archive.ZipWriter
import archive.bzip2
import archive.gzip
import archive.gzipSource
import fs.EntryKind
import fs.atomic
import fs.bytesIfExists
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.joinPath
import fs.mode
import fs.removeAny
import ketch.core.error.KetchError
import ketch.core.testing.withTempDir
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal fun octal(digits: String) = digits.toInt(8)

internal fun unlimited() = Budget(Long.MAX_VALUE, Long.MAX_VALUE)

internal fun source(bytes: ByteArray): RawSource = Buffer().apply { write(bytes) }

internal fun write(
    path: Path,
    bytes: ByteArray,
) = atomic(path, bytes)

internal fun bytes(text: String) = text.encodeToByteArray()

internal fun exists(path: Path) = entryKind(path) != null

internal fun isSymlink(path: Path) = entryKind(path) == EntryKind.Symlink

internal fun modeOf(path: Path) = checkNotNull(mode(path)) and octal("7777")

internal fun sizeOf(path: Path) = SystemFileSystem.metadataOrNull(path)?.size ?: 0

internal fun dir(
    root: Path,
    name: String,
) = joinPath(root, name).also { createDirAll(it) }

internal fun refusal(body: () -> Unit) = assertFailsWith<KetchError> { body() }.message

internal fun tarGzWith(vararg members: Triple<String, ByteArray, Int>) = gzip(TarWriter().apply { for ((name, body, mode) in members) file(name, body, mode) }.finish())

/**
 * The escape this guards against is not hypothetical: every member name passes `safeMemberPath` and
 * `checkLinkTarget`, because both reason about the name while the kernel resolves the link.
 */
internal fun plantedSymlinkTar() =
    TarWriter()
        .apply {
            // 1. A link back to the payload root. Lexical depth 4, four `..` land on 0, so the target guard
            //    accepts it.
            symlink("a/b/c/d/link", "../../../..")
            // 2. A link named *through* member 1, so its real depth is four rather than the nine its name
            //    claims — and the surplus `..` escapes.
            symlink("a/b/c/d/link/e/f/g/h/esc", "../../../../../../../../..")
            // 3. The payload, written through member 2.
            file("a/b/c/d/link/e/f/g/h/esc/victim", bytes("pwned"), octal("644"))
        }.finish()

// Fixed xz bytes from the Rust tests, so nothing here needs an xz encoder. `XZ_ZS` is 64 KiB of `z`; the
// tar fixtures are GNU tar, then xz.
internal val XZ_HELLO =
    (
        "fd377a585a000004e6d6b446020021010c0000008f98419c01000468656c6c6f00000000b137b9dbe5da1e9b00011d05b82d" +
            "80af1fb6f37d010000000004595a"
    ).hexToByteArray()
internal val XZ_ELF =
    (
        "fd377a585a000004e6d6b446020021010c0000008f98419c01000b7f454c46207061796c6f616400aa9ae31249a9880f0001" +
            "240ca618d8d81fb6f37d010000000004595a"
    ).hexToByteArray()
internal val XZ_ELF_LONG =
    (
        "fd377a585a000004e6d6b446020021010c0000008f98419c0100197f454c4620616e64207468656e20736f6d65207061796c" +
            "6f61640000000fae6508e04def350001321a201894301fb6f37d010000000004595a"
    ).hexToByteArray()
internal val XZ_ZS =
    (
        "fd377a585a000004e6d6b446020021010c0000008f98419ce0ffff004c5d003d6ffbbffea3b15ee5f83fb2aa2655f8687041" +
            "70150f8dfd1e4c1b8a42b719f4691871ae66238a8a4d2fa30dd97fa6e38c231153e05918c5758ae277f8b6947f0c6ac0de74" +
            "49645c9e3ad100007a6b16e253fc55f400016880800400009ff7447cb1c467fb020000000004595a"
    ).hexToByteArray()
internal val TAR_XZ_XY =
    (
        "fd377a585a000004e6d6b446020021010c0000008f98419ce027ff00535d003c00335018688982eda78069fada6df2b6a6df" +
            "1cb32e687b3f5cc974b0805e33e5dbdb5385773199bc6ba837ba2cf51213439a4101b1fd5f9631ee4614babd6837d691e72c" +
            "213b33a350f55312d5bb6e911e000000c78601615e8e016600016f805000000062d61c40b1c467fb020000000004595a"
    ).hexToByteArray()
internal val TAR_XZ_TOOL =
    (
        "fd377a585a000004e6d6b446020021010c0000008f98419ce027ff00615d00311a4a1b09c0f4112436e967130d68be2be8a7" +
            "000c72e7de9242d68a91e0c70cd6d2e6964c29fa2ff7d1c9c71134168adda2aa6549b28827db381b58292643877f81a78455" +
            "a3aeb1b89831657a2d3fb0948ddcf9fe9a996840de94be91c3de27000000000037bf4f3be061833300017d8050000000f275" +
            "020eb1c467fb020000000004595a"
    ).hexToByteArray()
