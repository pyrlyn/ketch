// `HKEY_CURRENT_USER` values ketch reads and writes: the user Path and cmd's AutoRun. Through PowerShell, which every
// Windows has, rather than the registry API, so the value kinds and the `WM_SETTINGCHANGE` broadcast stay as Rust has them.
package ketch.core.shell

import ketch.core.error.KetchError

/** Where the user environment lives, under `HKEY_CURRENT_USER`. */
private const val ENVIRONMENT_KEY = "Environment"

/** The key AutoRun lives in. */
private const val AUTORUN_KEY = "Software\\Microsoft\\Command Processor"

/**
 * The user Path's registry kind and raw value, `%VAR%` unexpanded; null when it is unset.
 * `[Environment]::GetEnvironmentVariable` would expand it, and writing that back freezes every `%VAR%` entry.
 */
internal fun readUserPathRaw(): Pair<String, String>? = registryStep("could not read the user PATH") { readUserValue(ENVIRONMENT_KEY, "Path") }

internal fun readUserPath(): String = readUserPathRaw()?.second.orEmpty()

/**
 * Write the user Path with its kind, then tell running programs.
 *
 * A registry write notifies nobody, so Explorer would hand new terminals the old PATH until a logoff.
 * `SetEnvironmentVariable` broadcasts `WM_SETTINGCHANGE`; setting and clearing a throwaway variable with it sends that
 * broadcast without touching Path's kind or `%VAR%` entries.
 */
internal fun writeUserPath(
    kind: String,
    value: String,
) = registryStep("could not write the user PATH") {
    writeUserValue(
        ENVIRONMENT_KEY,
        "Path",
        userPathKind(kind),
        value,
        "; [Environment]::SetEnvironmentVariable('KETCH_ENV_REFRESH', '1', 'User'); " +
            "[Environment]::SetEnvironmentVariable('KETCH_ENV_REFRESH', \$null, 'User')",
    )
}

/** AutoRun's kind and raw value, `%VAR%` unexpanded; null when it is unset. */
internal fun readAutorun(): Pair<String, String>? = registryStep("could not read cmd's AutoRun") { readUserValue(AUTORUN_KEY, "AutoRun") }

internal fun writeAutorun(
    kind: String,
    value: String,
) = registryStep("could not write cmd's AutoRun") { writeUserValue(AUTORUN_KEY, "AutoRun", kind, value, "") }

internal fun deleteAutorun() =
    registryStep("could not delete cmd's AutoRun") {
        powershell(
            PowerShell.Desktop.exe,
            "\$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(\$env:KETCH_AUTORUN_KEY, \$true); " +
                "if (\$k) { \$k.DeleteValue('AutoRun', \$false); \$k.Close() }",
            mapOf("KETCH_AUTORUN_KEY" to AUTORUN_KEY),
        )
    }

private inline fun <T> registryStep(
    what: String,
    step: () -> T,
): T =
    try {
        step()
    } catch (e: KetchError.Msg) {
        throw KetchError.Msg("$what: ${e.message}", e)
    }

/** A `HKEY_CURRENT_USER` value's kind and raw value; null when unset. */
private fun readUserValue(
    key: String,
    name: String,
): Pair<String, String>? {
    val out =
        powershell(
            PowerShell.Desktop.exe,
            "\$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(\$env:KETCH_REG_KEY); " +
                "if (\$k -and (\$k.GetValueNames() -contains \$env:KETCH_REG_NAME)) { " +
                "[Console]::Out.Write(\$k.GetValueKind(\$env:KETCH_REG_NAME).ToString() + [char]10 + " +
                "[string]\$k.GetValue(\$env:KETCH_REG_NAME, '', 'DoNotExpandEnvironmentNames')) }",
            mapOf("KETCH_REG_KEY" to key, "KETCH_REG_NAME" to name),
        )
    val split = out.indexOf('\n')
    return if (split < 0) null else out.substring(0, split).trim() to out.substring(split + 1)
}

/** Set a `HKEY_CURRENT_USER` value with an explicit kind; [then] is more script, fixed text only, run after the key is closed. */
private fun writeUserValue(
    key: String,
    name: String,
    kind: String,
    value: String,
    then: String,
) {
    powershell(
        PowerShell.Desktop.exe,
        "\$k = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey(\$env:KETCH_REG_KEY); " +
            "\$k.SetValue(\$env:KETCH_REG_NAME, \$env:KETCH_REG_VALUE, [Microsoft.Win32.RegistryValueKind]\$env:KETCH_REG_KIND); \$k.Close()$then",
        mapOf("KETCH_REG_KEY" to key, "KETCH_REG_NAME" to name, "KETCH_REG_VALUE" to value, "KETCH_REG_KIND" to kind),
    )
}
