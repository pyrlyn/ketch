// ketch's own man pages, rendered from the command tree: the port of src/man.rs.
//
// One page per visible command, `ketch.1` plus `ketch-<cmd>[-<sub>…].1`, so `man ketch-self-uninstall` shows that
// command's options and defaults. Kept apart from extra, which classifies the man pages a client app ships: this
// file only produces the host's own.
package ketch.cli

import cli.ManHeader
import cli.ManPage
import cli.manPages
import fs.atomic
import fs.createDirAll
import fs.joinPath
import ketch.core.VERSION
import ketch.core.error.ioAt
import ketch.core.log.timestamp
import ketch.core.model.nowUnix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv

/** Render every page, depth first, parents before their subcommands. */
internal fun pages(): List<ManPage> =
    manPages(ketchCli(), "ketch", ManHeader(pageDate(), "ketch $VERSION", "ketch manual")).map {
        ManPage(it.fileName, tidy(it.roff))
    }

/**
 * clap_mangen puts "Possible values" after a break, a blank line and another break (or, with no help text, a blank
 * line and a break). A blank line is already a paragraph space, so mandoc drops the breaks around it with a warning;
 * one `.sp` is the spacing either sequence was after.
 */
internal fun tidy(roff: String): String = roff.replace("\n.br\n\n.br\n", "\n.sp\n").replace("\n\n.br\n", "\n.sp\n")

/** The `.TH` date: `SOURCE_DATE_EPOCH` when a packager sets it, so one release renders the same bytes; else today. */
@OptIn(ExperimentalForeignApi::class)
private fun pageDate(): String {
    val secs = getenv("SOURCE_DATE_EPOCH")?.toKString()?.trim()?.toLongOrNull() ?: nowUnix()
    return timestamp(secs).take(DATE)
}

private const val DATE = 10

/** Write every page into [dir], creating it, and return the paths written. */
internal fun writeTo(dir: Path): List<Path> {
    ioAt(dir) { createDirAll(dir) }
    return pages().map { page ->
        val path = joinPath(dir, page.fileName)
        ioAt(path) { atomic(path, page.roff.encodeToByteArray()) }
        path
    }
}
