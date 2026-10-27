@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.fsio

import fs.FsException
import fs.removeAny
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv
import platform.posix.mkdtemp

/** Rust `tempfile::TempDir`: a fresh directory under `$TMPDIR`, removed with everything in it on [close]. */
class TempDir private constructor(
    val path: Path,
) : AutoCloseable {
    // Best effort, as Rust's drop is: a failed cleanup must not hide the error that ended the work.
    override fun close() {
        runCatching { removeAny(path) }
    }

    companion object {
        /** Create one named [prefix] and six random characters; fails as [FsException]. */
        fun create(prefix: String = ".tmp"): TempDir {
            val base = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
            val made = memScoped { mkdtemp("$base/${prefix}XXXXXX".cstr.ptr)?.toKString() }
            return TempDir(Path(made ?: throw FsException.ofErrno(Path(base))))
        }
    }
}
