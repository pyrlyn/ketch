// Reading a package's changelog, from either of the two places one lives.
//
// Projects record what changed in one of two ways, and most do both inconsistently: a `CHANGELOG.md` committed to the
// repository and shipped inside the release, and the notes attached to the release itself. The file is preferred when
// it exists, because reading it needs no network and it is the version the user actually has. Falling back to release
// notes covers every project that keeps its history only on the forge.
package ketch.core.changelog

import fs.FsException
import fs.isDir
import fs.listDir
import ketch.core.error.KetchError
import ketch.core.error.ioAt
import ketch.core.text.BYTE_ORDER
import ketch.core.text.rustTrim
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** File names worth looking for, most conventional first. */
private val NAMES =
    listOf(
        "CHANGELOG.md",
        "CHANGELOG",
        "CHANGELOG.txt",
        "CHANGES.md",
        "CHANGES",
        "HISTORY.md",
        "NEWS.md",
        "RELEASES.md",
        "RELEASE_NOTES.md",
    )

/** Directories a project might tuck it into, relative to the payload root. */
private val SUBDIRS = listOf("", "doc", "docs", "share/doc")

/**
 * Find a changelog file inside an installed payload.
 *
 * Only looks a fixed set of places rather than walking the tree: a release payload can contain thousands of files, and
 * the ones that ship a changelog put it where everyone else does.
 */
fun findFile(prefix: Path): Path? =
    SUBDIRS.firstNotNullOfOrNull { dir ->
        val base = if (dir.isEmpty()) prefix else Path(prefix, dir)
        // Some projects ship `share/doc/<pkg>/CHANGELOG.md`, one level deeper than the directory itself. One level,
        // not a walk. Sorted, so which copy wins does not depend on the directory's order on disk.
        namedIn(base) ?: subdirs(base).firstNotNullOfOrNull(::namedIn)
    }

private fun namedIn(dir: Path) = NAMES.map { Path(dir, it) }.firstOrNull { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }

/** A directory that cannot be listed simply has nothing in it worth reading. */
@Suppress("SwallowedException")
private fun subdirs(dir: Path): List<Path> =
    try {
        listDir(dir).sortedWith(BYTE_ORDER).map { Path(dir, it) }.filter(::isDir)
    } catch (_: FsException) {
        emptyList()
    }

/** Read a changelog file and take the section for [version], or the whole file when no section matches. */
fun fromFile(
    path: Path,
    version: String?,
): Entry {
    val bytes = ioAt(path) { SystemFileSystem.source(path).buffered().use { it.readByteArray() } }
    val raw =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw KetchError.Io(path, IOException("stream did not contain valid UTF-8", e))
        }
    val text = sanitize(raw)
    val (heading, body) = version?.let { section(text, it) } ?: (null to text.rustTrim())
    return Entry(Origin.File(path), heading, body)
}
