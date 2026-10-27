@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ketch.core.testing

import fs.removeAny
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv
import platform.posix.mkdtemp

/** Rust `tempfile::tempdir`: a fresh directory under `$TMPDIR`, removed afterwards. */
fun <T> withTempDir(body: (Path) -> T): T {
    val base = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
    val dir =
        memScoped {
            Path(checkNotNull(mkdtemp("$base/ketch-test-XXXXXX".cstr.ptr)) { "mkdtemp" }.toKString())
        }
    try {
        return body(dir)
    } finally {
        removeAny(dir)
    }
}
