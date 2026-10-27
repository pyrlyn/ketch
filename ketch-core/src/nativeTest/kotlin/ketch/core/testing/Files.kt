package ketch.core.testing

import fs.atomic
import fs.createDirAll
import fs.readText
import fs.setMode
import kotlinx.io.files.Path

/** Rust `std::fs::write` of a string. */
fun Path.write(text: String) = atomic(this, text.encodeToByteArray())

/** Rust `std::fs::read` of a file the test wrote as text. */
fun Path.read(): String = readText(this)

private val RWXR_XR_X = "755".toInt(8)

/** An executable script [name] in [dir], made with its parents. */
fun program(
    dir: Path,
    name: String,
    body: String = "#!/bin/sh\n",
): Path {
    createDirAll(dir)
    val path = Path(dir, name)
    path.write(body)
    setMode(path, RWXR_XR_X)
    return path
}
