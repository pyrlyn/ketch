package ketch.core.importer

import fs.readText
import ketch.core.testing.RUST_FIXTURES
import kotlinx.io.files.Path

/** A catalogue response Rust ketch recorded for its own tests: `import/<backend>/<name>`. */
internal fun fixture(
    backend: String,
    name: String,
) = readText(Path(Path(Path(RUST_FIXTURES, "import"), backend), name))
