package ketch.core.selfupdate

import ketch.core.process.powershellExe
import ketch.core.report.Report
import ketch.core.shell.nativeSpelling
import kotlinx.io.files.Path
import platform.posix.getpid
import process.Command
import process.ProcessException
import process.spawnDetached

// The paths travel in environment variables so a quote or `$` in one cannot break out of the script.
private const val FINISH_SCRIPT =
    "\$ErrorActionPreference = 'SilentlyContinue'; " +
        "Wait-Process -Id \$env:KETCH_WAIT_PID -Timeout 300; " +
        "foreach (\$p in (\$env:KETCH_LEFTOVERS -split \"`n\")) { if (\$p) { Remove-Item -LiteralPath \$p -Recurse -Force } }; " +
        "[IO.Directory]::Delete(\$env:KETCH_ROOT_DIR)"

/**
 * Windows will not delete the image of a running process, and that image is inside the root it is removing. A detached
 * PowerShell waits for this pid and finishes the job. It removes only the paths ketch named, and the root with a
 * non-recursive delete, so the root itself is never deleted with whatever else is in it.
 */
internal actual fun finishAfterExit(
    root: Path,
    left: List<Path>,
    report: Report,
): Boolean {
    val command =
        Command(powershellExe().toString(), listOf("-NoProfile", "-NonInteractive", "-Command", FINISH_SCRIPT))
            .env("KETCH_WAIT_PID", getpid().toString())
            .env("KETCH_LEFTOVERS", left.joinToString("\n") { nativeSpelling(it) })
            .env("KETCH_ROOT_DIR", nativeSpelling(root))
    // A working directory inside the root would itself keep it from going.
    root.parent?.let { command.currentDir(nativeSpelling(it)) }
    return try {
        command.spawnDetached()
        true
    } catch (e: ProcessException) {
        report.warn("could not schedule the removal of $root: ${e.detail}")
        false
    }
}
