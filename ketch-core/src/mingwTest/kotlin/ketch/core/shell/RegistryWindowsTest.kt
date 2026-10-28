package ketch.core.shell

import ketch.core.config.Config
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RegistryWindowsTest {
    /** The real registry: a `%USERPROFILE%` entry must survive a ketch edit unexpanded, and a non-ASCII one byte for byte. */
    @Test
    fun editingTheUserPathKeepsVariablesAndNonAsciiEntries() {
        val saved = readUserPathRaw()
        try {
            withTempDir { tmp ->
                val cfg = Config.load(Path(tmp, "kétch-ö"), Report.silent())
                val before = "%USERPROFILE%\\ketch-b95-probe;C:\\Zoë\\b95"
                writeUserPath("ExpandString", before)

                installUser(cfg, dryRun = false)
                val (kind, value) = assertNotNull(readUserPathRaw(), "the user PATH is set")
                assertEquals("ExpandString", kind)
                assertEquals("${nativeSpelling(cfg.binDir)};$before", value, "the edit must prepend the bin dir and change nothing else")

                uninstallUser(cfg, dryRun = false)
                assertEquals("ExpandString" to before, readUserPathRaw())
            }
        } finally {
            if (saved != null) {
                runCatching { writeUserPath(saved.first, saved.second) }
            } else {
                runCatching {
                    powershell(
                        PowerShell.Desktop.exe,
                        "\$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', \$true); " +
                            "if (\$k) { \$k.DeleteValue('Path', \$false); \$k.Close() }",
                    )
                }
            }
        }
    }
}
