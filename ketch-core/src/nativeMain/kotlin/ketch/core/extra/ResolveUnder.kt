package ketch.core.extra

import ketch.core.error.KetchError
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Join [rel] under [root], refusing anything that is not a regular file still inside [root]. */
fun resolveUnder(
    root: Path,
    rel: String,
): Path {
    val target = Path(root, contained(rel))
    if (!target.toString().startsWith("$root/")) throw KetchError.Msg("extra path `$rel` must stay inside the package")
    if (SystemFileSystem.metadataOrNull(target)?.isRegularFile != true) {
        throw KetchError.Msg("extra path `$rel` is not a file in the payload")
    }
    return target
}
