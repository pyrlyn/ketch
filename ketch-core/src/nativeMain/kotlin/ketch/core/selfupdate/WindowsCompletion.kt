package ketch.core.selfupdate

import ketch.core.error.KetchError
import ketch.core.report.Ctx
import ketch.core.shell.Outcome
import ketch.core.shell.installCmdMacros
import ketch.core.shell.installPowershellProfiles
import kotlinx.io.files.Path

/** Switch completion on for PowerShell and cmd. Best effort: completion is worth a warning when it cannot be set up, never a failed install. */
internal fun enableWindowsCompletion(
    cx: Ctx,
    script: Path?,
) {
    val report = cx.report
    if (script != null) {
        try {
            for (change in installPowershellProfiles(script)) {
                val what = "${change.shell.label} completion in ${change.file}"
                when (change.outcome) {
                    Outcome.Added -> {
                        report.success("added", what)
                    }

                    Outcome.Updated -> {
                        report.success("updated", what)
                    }

                    null -> {
                        report.note("${change.shell.label} completion not enabled: ${change.leftAlone}")
                    }

                    else -> {}
                }
            }
        } catch (e: KetchError) {
            report.warn("could not enable PowerShell completion: ${e.message}")
        }
    }
    try {
        if (installCmdMacros(cx.cfg) == Outcome.Added) {
            report.success("added", "cmd macros ki, ku, kl, kun (HKCU\\Software\\Microsoft\\Command Processor\\AutoRun)")
        }
    } catch (e: KetchError) {
        report.warn("could not add the cmd macros: ${e.message}")
    }
}
