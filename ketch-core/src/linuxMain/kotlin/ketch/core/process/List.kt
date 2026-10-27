package ketch.core.process

import fs.bytesIfExists
import fs.listDir
import fs.readLink
import ketch.core.report.Report
import kotlinx.io.files.Path

/** `/proc`: each process's executable, its open files, and the command line that may name a script. */
internal actual fun list(
    keys: List<Pair<Path, String>>,
    report: Report,
): List<Occupant> =
    runCatching { listDir(Path("/proc")) }.getOrDefault(emptyList()).mapNotNull { name ->
        val pid = name.toUIntOrNull()?.toLong() ?: return@mapNotNull null
        val base = Path("/proc", name)
        val exe = readLink(Path(base, "exe"))
        val cmdline = runCatching { bytesIfExists(Path(base, "cmdline")) }.getOrNull()?.decodeToString().orEmpty()
        keys
            .firstOrNull { (path, key) ->
                exe?.let { matchesExe(it, path, key) } == true || fdHits(base, path, key) || argsHit(cmdline, path, key)
            }?.let { (path, _) -> Occupant(pid, path) }
    }

private fun fdHits(
    procDir: Path,
    candidate: Path,
    key: String,
): Boolean {
    val fds = runCatching { listDir(Path(procDir, "fd")) }.getOrDefault(emptyList())
    return fds.any { fd -> readLink(Path(procDir, "fd", fd))?.let { matchesExe(it, candidate, key) } == true }
}
