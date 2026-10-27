package ketch.core.extra

import ketch.core.model.LinkRole
import kotlinx.io.files.Path

/** One extra file ready to place: a payload-relative source and a destination the platform already named. */
data class ExtraPlacement(
    /** Path relative to the extracted payload or store prefix. */
    val relPath: String,
    /** Absolute path ketch will create and record. */
    val dest: Path,
    /** Recorded on the link so uninstall and `binaries()` tell this apart from a PATH link. */
    val role: LinkRole,
)
