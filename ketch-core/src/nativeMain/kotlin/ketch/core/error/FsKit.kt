package ketch.core.error

import fs.FsException
import kotlinx.io.IOException
import kotlinx.io.files.Path

/** Rust `Error::fs_kit`: the path the file layer names wins over [path], so the message names the failing one once. */
fun KetchError.Companion.fsKit(
    path: Path,
    e: FsException,
): KetchError = KetchError.Io(e.path ?: path, e.withoutPath())

/** Run [body], turning a file-layer error into [KetchError.Io] the way [fsKit] names it. */
inline fun <T> fsKitAt(
    path: Path,
    body: () -> T,
): T =
    try {
        body()
    } catch (e: FsException) {
        throw KetchError.fsKit(path, e)
    }

/** Rust `Error::io(path, e)`: [path] names the failure, whatever path the file layer had. */
fun KetchError.Companion.io(
    path: Path,
    e: IOException,
): KetchError = KetchError.Io(path, if (e is FsException) e.withoutPath() else e)

/** Run [body], turning a file error into [KetchError.Io] at [path] (Rust `.map_err(|e| Error::io(path, e))`). */
inline fun <T> ioAt(
    path: Path,
    body: () -> T,
): T =
    try {
        body()
    } catch (e: IOException) {
        throw KetchError.io(path, e)
    }
