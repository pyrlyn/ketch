package ketch.core.error

import fs.FsException
import kotlinx.io.files.Path

/** Rust `Error::fs_kit`: the path the file layer names wins over [path], so the message names the failing one once. */
fun KetchError.Companion.fsKit(
    path: Path,
    e: FsException,
): KetchError = KetchError.Io(e.path ?: path, e.withoutPath())
