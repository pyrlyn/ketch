// PowerShell on Windows: its profiles, which ketch adds a completion block to, and running it, which is how ketch
// reads and writes the user's registry values.
package ketch.core.shell

import ketch.core.config.HOST_WINDOWS
import ketch.core.error.KetchError
import ketch.core.platform.documentsDir
import kotlinx.io.files.Path
import process.Command
import process.ProcessException
import process.runBounded
import kotlin.time.Duration.Companion.seconds

/**
 * Rust runs PowerShell without a deadline. A cold Windows PowerShell start takes seconds, not minutes, and one that
 * hangs (a broken module path, a stuck policy lookup) must not hang `ketch path install` with it.
 */
private val POWERSHELL_TIMEOUT = 120.seconds

/** A registry value or a policy name is a line; anything near this size is not what was asked for. */
private const val POWERSHELL_OUTPUT = 1L shl 20

/** The Windows PowerShell editions, each with its own profile directory. */
enum class PowerShell(
    /** What the user calls it. */
    val label: String,
    internal val exe: String,
    private val dir: String,
) {
    /** PowerShell 7 (`pwsh`), profile in `Documents\PowerShell`. */
    Core("PowerShell 7", "pwsh", "PowerShell"),

    /** Windows PowerShell 5.1 (`powershell`), profile in `Documents\WindowsPowerShell`. */
    Desktop("Windows PowerShell", "powershell", "WindowsPowerShell"),
    ;

    /** The CurrentUserAllHosts profile under [documents]: what `$PROFILE.CurrentUserAllHosts` names, read by every host. */
    fun profile(documents: Path) = Path(Path(documents, dir), "profile.ps1")

    companion object {
        /** Both editions, the current one first. */
        val ALL = entries.toList()
    }
}

/** What happened to one PowerShell profile. */
data class ProfileChange(
    val shell: PowerShell,
    val file: Path,
    /** Null when the profile was left alone, and then [leftAlone] says why; that is not a failure. */
    val outcome: Outcome?,
    val leftAlone: String? = null,
)

/**
 * The profile block: dot-source [script] when it is there. `Test-Path` first, because the script is a link
 * `self uninstall` removes, and a profile that errors on every start is worse than no completion.
 */
internal fun powershellBlock(script: String): String {
    val path = quotePowershell(script)
    return "$BEGIN\nif (Test-Path -LiteralPath $path) { . $path }\n$END\n"
}

/** Single-quote for PowerShell. It treats the typographic single quotes as quote characters too, so each is doubled like `'`. */
internal fun quotePowershell(text: String): String =
    buildString(text.length + 2) {
        append('\'')
        for (c in text) {
            if (c in POWERSHELL_QUOTES) append(c)
            append(c)
        }
        append('\'')
    }

private const val POWERSHELL_QUOTES = "'‘’‚‛"

/**
 * [text] with the profile block for [script] in it, or null when it already says exactly that.
 *
 * Windows PowerShell 5.1 reads a profile without a byte order mark in the ANSI code page, so a new file whose block
 * names a non-ASCII path starts with one. An existing file keeps whatever encoding its owner chose.
 */
internal fun spliceProfile(
    text: String,
    script: String,
): String? {
    val block = powershellBlock(script)
    if (text.isEmpty() && block.any { it.code > ASCII_MAX }) return "$BOM$block"
    return splice(text, block)
}

private const val ASCII_MAX = 0x7f

internal fun runsLocalScripts(policy: String) = listOf("Unrestricted", "RemoteSigned", "Bypass").any { policy.trim().equals(it, ignoreCase = true) }

/**
 * Add the block that dot-sources [script] to both editions' profiles.
 *
 * A profile that does not exist yet is created only when that edition is installed and its execution policy runs local
 * scripts. Windows PowerShell ships with `Restricted` on client editions: a new profile there would put an error on
 * every start and complete nothing.
 */
fun installPowershellProfiles(script: Path): List<ProfileChange> {
    val text = nativeSpelling(script)
    if ('\n' in text || '\r' in text) throw KetchError.Msg("$text contains a newline")
    val documents = powershellDocuments()
    return PowerShell.ALL.map { shell ->
        val file = shell.profile(documents)
        val why = if (isFile(file)) null else whyLeftAlone(shell)
        if (why != null) {
            ProfileChange(shell, file, null, why)
        } else {
            val current = read(file)
            val hadBlock = blockSpan(current) != null
            val next = spliceProfile(current, text)
            next?.let { write(file, it) }
            val outcome =
                when {
                    next == null -> Outcome.Unchanged
                    hadBlock -> Outcome.Updated
                    else -> Outcome.Added
                }
            ProfileChange(shell, file, outcome)
        }
    }
}

/** Why a missing profile for [shell] is not created; null when it may be. */
private fun whyLeftAlone(shell: PowerShell): String? {
    val policy = executionPolicy(shell) ?: return "${shell.exe} is not installed"
    return if (runsLocalScripts(policy)) {
        null
    } else {
        "its execution policy is $policy; run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` there and install completions again"
    }
}

/** Every PowerShell profile holding a ketch block. Empty off Windows, and when Documents cannot be found. */
fun powershellProfilesWithBlock(): List<Path> {
    val documents = if (HOST_WINDOWS) runCatching { powershellDocuments() }.getOrNull() else null
    return documents?.let { docs -> PowerShell.ALL.map { it.profile(docs) }.filter { readable(it)?.let(::blockSpan) != null } }.orEmpty()
}

/** The effective execution policy of one edition, or null when it does not run. */
private fun executionPolicy(shell: PowerShell): String? = runCatching { powershell(shell.exe, "Get-ExecutionPolicy") }.getOrNull()?.trim()

/**
 * The Documents folder, as PowerShell itself resolves it.
 *
 * Asked of the shell rather than built from the home directory: OneDrive and group policy move Documents, and the
 * profile PowerShell reads is under wherever it went. `DoNotVerify`, because without it Windows PowerShell answers an
 * empty string for a Documents folder not created yet.
 */
private fun powershellDocuments(): Path {
    val asked =
        runCatching { powershell(PowerShell.Desktop.exe, "[Console]::Out.Write([Environment]::GetFolderPath('MyDocuments', 'DoNotVerify'))") }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::Path)
    return asked ?: documentsDir() ?: throw KetchError.Msg("could not find the Documents folder")
}

/**
 * Run a PowerShell command and return its standard output.
 *
 * Anything variable goes in through [env], never into [script]: a value holding a quote or `$` cannot then break out
 * of the command. Output is forced to UTF-8 so a non-ASCII path survives the console code page.
 */
internal fun powershell(
    exe: String,
    script: String,
    env: Map<String, String> = emptyMap(),
): String {
    // Without a console to change, the assignment throws; the default encoding is then the only one there is.
    val command = Command(exe, listOf("-NoProfile", "-NonInteractive", "-Command", "try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }; $script"))
    env.forEach { (k, v) -> command.env(k, v) }
    val out =
        try {
            command.runBounded(POWERSHELL_TIMEOUT, POWERSHELL_OUTPUT)
        } catch (e: ProcessException) {
            throw KetchError.Msg("could not run $exe: ${e.detail}", e)
        }
    if (!out.status.success) throw KetchError.Msg("$exe failed: ${out.stderr.decodeToString().trim()}")
    return out.stdout.decodeToString()
}
