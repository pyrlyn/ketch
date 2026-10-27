package ketch.core.fsio

import fs.FsException
import fs.WriteOptions
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import kotlinx.io.files.Path

/**
 * The state, lock and cache files were written that way when each built its own temp file (a temp file is born
 * `0600`, and a rename over a link replaces the link); callers rely on it.
 */
private val PRIVATE =
    WriteOptions(createParent = true, preservePermissions = false, followSymlinks = false, mode = 0b110_000_000)

/** Replace [path] with [bytes], creating its parent; the new file is `0600` and a symlink there is replaced. */
internal fun writePrivate(
    path: Path,
    bytes: ByteArray,
) = try {
    PRIVATE.write(path, bytes)
} catch (e: FsException) {
    throw KetchError.fsKit(path, e)
}
