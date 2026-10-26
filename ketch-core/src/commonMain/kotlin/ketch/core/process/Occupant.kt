package ketch.core.process

import kotlinx.io.files.Path

/** A process whose executable or command line names a file being replaced. */
data class Occupant(
    /** OS process id. */
    val pid: Long,
    /** The replacement path that matched this process. */
    val path: Path,
)
