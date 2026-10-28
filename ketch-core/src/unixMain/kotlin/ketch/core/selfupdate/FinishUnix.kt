package ketch.core.selfupdate

import ketch.core.report.Report
import kotlinx.io.files.Path

/** Here a running binary can be deleted, so what is left now stays left: the warnings already named it. */
@Suppress("FunctionOnlyReturningConstant")
internal actual fun finishAfterExit(
    root: Path,
    left: List<Path>,
    report: Report,
): Boolean = false
