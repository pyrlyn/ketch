// Windows completion, driven through the real shells: PowerShell's own `TabExpansion2` against the script
// `ketch completions powershell` prints, and cmd's AutoRun and the PowerShell profiles that `completions --install`
// writes and `self uninstall` takes back (Rust tests/completion_windows.rs).
//
// The second half touches the real `HKCU\Software\Microsoft\Command Processor\AutoRun` and the profiles under
// Documents: neither can be pointed at a sandbox. Both are saved first and put back in a `finally`, whether the
// assertions pass or not.
package ketch.e2e

import fs.bytesIfExists
import fs.copyFile
import fs.joinPath
import fs.removeFile
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.Sandbox
import ketch.e2e.support.ketch
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import ketch.e2e.support.writeBytes
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import process.Command
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompletionWindowsTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    /** The directory holding the built `ketch.exe`, first on PATH so the completer's `ketch __complete` reaches this build. */
    private val exeDir = checkNotNull(Path(KETCH_EXE).parent) { "$KETCH_EXE has no parent directory" }

    /** Run a PowerShell command with the sandbox environment and return stdout. */
    private fun powershell(
        shell: String,
        command: String,
        policy: List<String> = emptyList(),
    ): String {
        val out = sandbox.ketchFrom(Path(shell), listOf("-NoProfile", "-NonInteractive") + policy + listOf("-Command", command), exeDir)
        assertTrue(out.success, "$shell failed:\n${out.stderr}")
        return out.stdout
    }

    /** What [shell] offers on <TAB> at the end of [line]. */
    private fun complete(
        shell: String,
        line: String,
    ): List<String> {
        val script = sandbox.fixture("ketch-completion.ps1")
        writeText(script, sandbox.ok("completions", "powershell"))
        val command =
            ". '$script'; (TabExpansion2 -inputScript '$line' -cursorColumn ${line.length}).CompletionMatches | ForEach-Object { \$_.CompletionText }"
        // Windows PowerShell refuses every script under its default policy, and says so only on stderr. This test is
        // about what the script completes, so it lifts the policy for its own process alone; the install test below
        // is the one that minds the machine's policy.
        return powershell(shell, command, listOf("-ExecutionPolicy", "Bypass"))
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
    }

    @Test
    fun bothPowershellsCompleteSubcommandsAndRegistryPackages() {
        sandbox.registryPackage("ripcord", "name = \"ripcord\"\nsource = \"test:ripcord\"\n")
        for (shell in listOf("pwsh", "powershell")) {
            val subs = complete(shell, "ketch ins")
            assertTrue("install" in subs, "$shell: $subs")
            assertEquals(listOf("ripcord"), complete(shell, "ketch install ri"), shell)
        }
    }

    /** cmd's AutoRun: kind and raw value, or null when unset. */
    private fun readAutorun(): Pair<String, String>? {
        val out =
            powershell(
                "powershell",
                "\$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\\Microsoft\\Command Processor'); " +
                    "if (\$k -and (\$k.GetValueNames() -contains 'AutoRun')) { " +
                    "[Console]::Out.Write(\$k.GetValueKind('AutoRun').ToString() + [char]10 + " +
                    "[string]\$k.GetValue('AutoRun', '', 'DoNotExpandEnvironmentNames')) }",
            )
        val newline = out.indexOf('\n').takeIf { it >= 0 } ?: return null
        return out.substring(0, newline).trim() to out.substring(newline + 1)
    }

    private fun writeAutorun(value: Pair<String, String>?) {
        val script =
            if (value != null) {
                "\$k = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\\Microsoft\\Command Processor'); " +
                    "\$k.SetValue('AutoRun', \$env:TEST_AUTORUN_VALUE, [Microsoft.Win32.RegistryValueKind]\$env:TEST_AUTORUN_KIND); \$k.Close()"
            } else {
                "\$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\\Microsoft\\Command Processor', \$true); " +
                    "if (\$k) { \$k.DeleteValue('AutoRun', \$false); \$k.Close() }"
            }
        val out =
            Command("powershell", listOf("-NoProfile", "-NonInteractive", "-Command", script))
                .env("TEST_AUTORUN_VALUE", value?.second.orEmpty())
                .env("TEST_AUTORUN_KIND", value?.first.orEmpty())
                .runOnce()
        assertTrue(out.success, "could not write AutoRun: $out")
    }

    @Test
    fun installAddsTheDoskeyLineAndProfileBlocksAndSelfUninstallRestoresThem() {
        val documents = Path(powershell("powershell", "[Console]::Out.Write([Environment]::GetFolderPath('MyDocuments', 'DoNotVerify'))").trim())
        val profiles = listOf("PowerShell", "WindowsPowerShell").map { joinPath(documents, it, "profile.ps1") }
        val autorun = readAutorun()
        val before = profiles.map { bytesIfExists(it) }
        try {
            val earlier = "String" to "set KETCH_TEST_AUTORUN=1"
            writeAutorun(earlier)

            // A package named ketch is what `completions --install` places docs for.
            val exe = sandbox.fixture("ketch.exe")
            copyFile(Path(KETCH_EXE), exe)
            sandbox.ok("install", "--path", exe.toString(), "--name", "ketch", "-y")
            val out = sandbox.ketch("completions", "powershell", "--install")
            val installed = out.stdout + out.stderr
            assertTrue(out.success, installed)

            val doskey =
                sandbox.root
                    .resolve("share")
                    .resolve("ketch")
                    .resolve("ketch.doskey")
            val macros = assertNotNull(bytesIfExists(doskey), "the macro file").decodeToString()
            assertTrue("ki=ketch install $*" in macros, macros)
            val (kind, value) = assertNotNull(readAutorun(), installed)
            assertEquals("String", kind)
            // The root may be spelled differently in the value (a short 8.3 name), so the ends are checked rather
            // than the whole path.
            assertTrue(
                value.startsWith("set KETCH_TEST_AUTORUN=1 & doskey /macrofile=\"") && value.endsWith("\\share\\ketch\\ketch.doskey\""),
                value,
            )

            // A new cmd runs AutoRun, so the macros are there without anything else. doskey keeps macros per
            // console, and a test has none of its own: kit gives a piped child a hidden one.
            val run = Command("cmd", listOf("/c", "doskey /macros & echo autorun=%KETCH_TEST_AUTORUN%")).runOnce()
            val listed = run.stdout + run.stderr
            assertTrue("autorun=1" in listed, "AutoRun did not run:\n$listed")
            assertTrue("ki=ketch install $*" in listed, listed)

            val policy = powershell("powershell", "Get-ExecutionPolicy").trim()
            val runsScripts = policy in listOf("Unrestricted", "RemoteSigned", "Bypass")
            val desktop = bytesIfExists(profiles[1])?.decodeToString().orEmpty()
            if (runsScripts || before[1] != null) {
                assertTrue(
                    "# >>> ketch >>>" in desktop && "ketch.ps1" in desktop,
                    "${profiles[1]} holds:\n$desktop\n--- completions --install said ---\n$installed",
                )
            }

            sandbox.ok("self", "uninstall", "--yes")

            assertEquals(earlier, readAutorun())
            assertFalse(SystemFileSystem.exists(doskey), "the macro file survived")
            for ((file, was) in profiles.zip(before)) {
                val now = bytesIfExists(file)
                if (was == null) assertEquals(null, now, "$file is not what it was") else assertContentEquals(was, now, "$file is not what it was")
            }
        } finally {
            writeAutorun(autorun)
            for ((file, bytes) in profiles.zip(before)) {
                if (bytes != null) runCatching { writeBytes(file, bytes) } else runCatching { removeFile(file) }
            }
        }
    }
}
