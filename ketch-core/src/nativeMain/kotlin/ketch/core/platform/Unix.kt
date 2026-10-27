package ketch.core.platform

import fs.FsException
import fs.mode
import fs.setMode
import ketch.core.error.ioAt
import kotlinx.io.files.Path

private val OWNER_READ_EXECUTE = "500".toInt(8)

/** Give the owner read and execute on [path], leaving every other bit as it is; a file already runnable is untouched. */
fun ensureExecutable(path: Path) {
    val bits = ioAt(path) { mode(path) ?: throw FsException.ofErrno(path) }
    if (bits and OWNER_READ_EXECUTE == OWNER_READ_EXECUTE) return
    ioAt(path) { setMode(path, bits or OWNER_READ_EXECUTE) }
}

/** Create [link] holding [target] as written. */
fun symlink(
    target: Path,
    link: Path,
) = ioAt(link) { fs.symlink(target, link) }
