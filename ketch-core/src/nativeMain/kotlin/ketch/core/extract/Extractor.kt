// Turning a downloaded file into a directory of files.
//
// Extractors are selected by sniffing content, not by trusting the file name: release assets are routinely named
// `.tar.gz` while being a plain binary, or `.zip` while being a tarball.
package ketch.core.extract

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.isDir
import fs.listDir
import fs.readLink
import ketch.core.error.KetchError
import ketch.core.error.ioAt
import ketch.core.report.Report
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.ENOENT
import platform.posix.errno

/** One archive format. */
interface Extractor {
    /** Stable identifier used in logs and `--verbose` output. */
    val id: String

    /** Can this extractor handle the file? [head] is the first 512 bytes, already read, so implementations do not each re-open the file. */
    fun detect(
        path: Path,
        head: ByteArray,
    ): Boolean

    /** Unpack [src] into the existing directory [dest]. */
    fun extract(
        src: Path,
        dest: Path,
    )
}

/** Pick an extractor and run it. Returns the id of the one that ran, and traces the choice on [report]. */
fun extractAuto(
    src: Path,
    dest: Path,
    extractors: List<Extractor>,
    report: Report,
): String {
    val head = readHead(src)
    ioAt(dest) { createDirAll(dest) }
    val extractor = extractors.firstOrNull { it.detect(src, head) } ?: throw KetchError.UnsupportedArchive(src)
    report.debug("extracting $src with `${extractor.id}`")
    extractor.extract(src, dest)
    return extractor.id
}

/** How many leading bytes detection looks at. */
internal const val HEAD_LEN = 512

/** First 512 bytes of a file, or fewer if it is shorter. */
fun readHead(path: Path): ByteArray = ioAt(path) { SystemFileSystem.source(path).use { readUpTo(it, HEAD_LEN) } }

/** Up to [want] bytes of [source]; fewer only at its end. */
internal fun readUpTo(
    source: RawSource,
    want: Int,
): ByteArray = Buffer().also { fill(source, it, want) }.readByteArray()

/** Read [source] into [buffer] until it holds [want] bytes or the source ends; what was read stays if a read fails. */
internal fun fill(
    source: RawSource,
    buffer: Buffer,
    want: Int,
) {
    do {
        val n = source.readAtMostTo(buffer, want - buffer.size)
    } while (n != -1L && buffer.size < want)
}

/** More links than this on one path is a loop, as the kernel decides too. */
private const val MAX_LINK_HOPS = 40

/**
 * True when [path], with every symlink along it and at its end followed, stays under [root].
 *
 * A link target checked on its own text cannot answer this: `L -> .` makes `N -> L/..` look one level deep while it
 * resolves to the parent of the payload. Links are followed one component at a time rather than through
 * `realpath`, so a dangling link is judged by where it would point, not waved through because it does not resolve
 * yet. Anything that cannot be read, or loops, counts as outside.
 */
internal fun resolvesInside(
    path: Path,
    root: Path,
): Boolean {
    val hops = Hops()
    val inside = resolve(root.toString(), hops)
    val resolved = resolve(path.toString(), hops)
    return inside != null && resolved != null && resolved.take(inside.size) == inside
}

private class Hops {
    var count = 0
}

/** The components of [path] with every existing symlink on it followed and `..` applied, or null on a loop or an unreadable entry. */
private fun resolve(
    path: String,
    hops: Hops,
): List<String>? {
    val absolute = if (path.startsWith('/')) path else currentDir()?.let { "$it/$path" } ?: return null
    return absolute.split('/').filter { it.isNotEmpty() && it != "." }.fold(emptyList<String>() as List<String>?) { at, part ->
        when {
            at == null -> null
            part == ".." -> at.dropLast(1)
            else -> follow(at, part, hops)
        }
    }
}

/** [resolved] with [name] appended and, when that is a link, followed. */
private fun follow(
    resolved: List<String>,
    name: String,
    hops: Hops,
): List<String>? {
    val next = resolved + name
    val at = Path(next.joinToString("/", prefix = "/"))
    return when (entryKind(at)) {
        EntryKind.Symlink -> {
            hops.count++
            val target = readLink(at)?.toString()
            when {
                hops.count > MAX_LINK_HOPS || target == null -> null
                target.startsWith('/') -> resolve(target, hops)
                else -> resolve(resolved.joinToString("/", prefix = "/", postfix = "/") + target, hops)
            }
        }

        // Nothing there yet: what a dangling link names is judged by the path alone.
        null -> {
            next.takeIf { errno == ENOENT }
        }

        else -> {
            next
        }
    }
}

private fun currentDir(): String? = runCatching { SystemFileSystem.resolve(Path(".")).toString() }.getOrNull()

/**
 * True when a directory name is itself a macOS bundle rather than a container of files.
 *
 * A bundle is a directory to everything below the filesystem and a single opaque item to everything above it, which
 * is exactly the distinction [unwrapSingleDir] and app discovery both need.
 */
fun isBundleName(name: String) = name.endsWith(".app") || name.endsWith(".framework")

/**
 * If the payload is a single wrapper directory, return it.
 *
 * Almost every release tarball unpacks to `tool-1.2.3-target/`; treating that wrapper as the payload root is what
 * makes `bin` paths in manifests short and stable across versions.
 */
fun unwrapSingleDir(dir: Path): Path {
    // Metadata directories macOS and archivers leave behind are not payload.
    val entries = ioAt(dir) { listDir(dir) }.filter { it != "__MACOSX" && it != ".DS_Store" }
    val only = entries.singleOrNull() ?: return dir
    // A lone `.app` is the commonest shape a macOS zip or dmg has, and it is the payload — not a wrapper around it.
    // Unwrapping here would hand back the bundle's `Contents`, and nothing downstream would ever see an app to
    // install.
    return Path(dir, only).takeIf { isDir(it) && !isBundleName(only) } ?: dir
}
