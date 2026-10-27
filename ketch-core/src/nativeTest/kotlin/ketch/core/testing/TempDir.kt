package ketch.core.testing

import fs.TempDir
import kotlinx.io.files.Path

/** Rust `tempfile::tempdir`: a fresh directory under `$TMPDIR`, removed afterwards. */
fun <T> withTempDir(body: (Path) -> T): T = TempDir.create("ketch-test-").use { body(it.path) }
