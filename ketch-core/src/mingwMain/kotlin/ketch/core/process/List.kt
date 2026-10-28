package ketch.core.process

import ketch.core.config.envOs
import ketch.core.report.Report
import kotlinx.io.files.Path
import process.Command

/** Windows PowerShell by full path: a `powershell.exe` earlier on PATH is not trusted to answer. */
internal fun powershellExe(): Path = Path(envOs("SystemRoot")?.let(::Path) ?: Path("C:\\Windows"), "System32\\WindowsPowerShell\\v1.0\\powershell.exe")

private const val LISTING =
    "Get-CimInstance Win32_Process | ForEach-Object { '{0}\t{1}\t{2}' -f \$_.ProcessId, \$_.ExecutablePath, \$_.CommandLine }"

/** Every process's executable and command line, from one CIM query: Windows has no `lsof`, and no `/proc` to read. */
internal actual fun list(
    keys: List<Pair<Path, String>>,
    report: Report,
): List<Occupant> {
    val out =
        outputBounded(Command(powershellExe().toString(), listOf("-NoProfile", "-NonInteractive", "-Command", LISTING)), LIST_TIMEOUT, report)
            ?: return emptyList()
    return out.stdout.decodeToString().lines().mapNotNull { line ->
        val cols = line.split('\t', limit = 3)
        val pid = cols[0].trim().toUIntOrNull() ?: return@mapNotNull null
        val exe = cols.getOrElse(1) { "" }
        val cmdline = cols.getOrElse(2) { "" }
        keys
            .firstOrNull { (path, key) -> (exe.isNotEmpty() && matchesExe(Path(exe), path, key)) || cmdHits(cmdline, path, key) }
            ?.let { (path, _) -> Occupant(pid.toLong(), path) }
    }
}
