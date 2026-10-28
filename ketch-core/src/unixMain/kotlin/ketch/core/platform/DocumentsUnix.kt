package ketch.core.platform

import kotlinx.io.files.Path

/** Only the Windows backend asks, for PowerShell's completions; on a Unix host its tests fall back to the data home. */
internal actual fun documentsDir(): Path? = null

/** Only Windows asks, for where mise keeps its installs; Unix has the XDG data home. */
internal actual fun localDataDir(): Path? = null
