package ketch.core.process

import ketch.core.report.Report
import kotlinx.io.files.Path
import process.Command

/** `lsof -t` names every process holding the file open, the running executable included. */
internal actual fun list(
    keys: List<Pair<Path, String>>,
    report: Report,
): List<Occupant> =
    keys.flatMap { (path, _) ->
        val out = outputBounded(Command("lsof", listOf("-t", "--", path.toString())), LIST_TIMEOUT, report)
        out
            ?.stdout
            ?.decodeToString()
            ?.lines()
            .orEmpty()
            .mapNotNull { it.trim().toUIntOrNull() }
            .map { Occupant(it.toLong(), path) }
    }
