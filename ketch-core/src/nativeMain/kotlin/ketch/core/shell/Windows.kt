// The Windows side of PATH and completion setup: the user environment and cmd's doskey macros, both in
// `HKEY_CURRENT_USER`, read and written through PowerShell. Off Windows there is no registry ketch writes, so the
// queries answer "nothing here" and only the file cleanup is real; the edits are only ever called on Windows.
package ketch.core.shell

import fs.FsException
import fs.WriteOptions
import fs.canon
import fs.entryKind
import fs.joinPath
import fs.removeFile
import ketch.core.config.Config
import ketch.core.config.HOST_WINDOWS
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.platform.DoctorCheck
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * True when the Windows user PATH already names the bin dir.
 *
 * Off Windows this is always false: there is no user environment ketch owns. An unreadable registry is treated as not
 * configured, like an unreadable startup file.
 */
fun userPathConfigured(cfg: Config): Boolean = HOST_WINDOWS && runCatching { windowsPathHas(readUserPath(), cfg.binDir) }.getOrDefault(false)

/** [Status.userPath]: null off Windows, where there is no user PATH to report on. */
internal fun userPathOrNull(cfg: Config): Boolean? = if (HOST_WINDOWS) userPathConfigured(cfg) else null

/**
 * Put the bin dir on the Windows user PATH.
 *
 * Edits the raw registry value (see [writeUserPath]) so `%VAR%` entries keep expanding, and Explorer is notified so a
 * new terminal sees the change without a logoff. `setx` is not used: it truncates PATH at 1024 characters.
 */
fun installUser(
    cfg: Config,
    dryRun: Boolean,
): Outcome {
    val current = readUserPathRaw()
    val next = windowsPathPrepend(current?.second.orEmpty(), cfg.binDir) ?: return Outcome.Unchanged
    if (!dryRun) writeUserPath(userPathKind(current?.first), next)
    return Outcome.Added
}

/** Take the bin dir back out of the Windows user PATH. */
fun uninstallUser(
    cfg: Config,
    dryRun: Boolean,
): Outcome {
    val (kind, current) = readUserPathRaw() ?: return Outcome.Unchanged
    return windowsPathRemove(current, cfg.binDir)?.let { next ->
        if (!dryRun) writeUserPath(kind, next)
        Outcome.Removed
    } ?: Outcome.Unchanged
}

/**
 * A value ketch writes into the Windows registry.
 *
 * `self uninstall` removes every entry [registryEntries] finds, so a new value ketch starts writing is added here
 * rather than as one more step in the uninstall, one that a later change could forget.
 */
enum class RegistryEntry {
    /** The bin dir in `HKCU\Environment\Path`, written by `install.ps1` and `ketch path install`. */
    UserPath,

    /** The command in `HKCU\Software\Microsoft\Command Processor\AutoRun` that loads ketch's doskey macros. */
    CmdMacros,
    ;

    /** How the entry is named when the user is asked to remove it. */
    fun describe() =
        when (this) {
            UserPath -> "the user PATH"
            CmdMacros -> "the cmd macros in HKCU\\Software\\Microsoft\\Command Processor\\AutoRun"
        }

    /**
     * True when the packages ketch installed rely on the entry, so `self uninstall --keep-packages` leaves it; false
     * when it only loads ketch itself.
     */
    fun servesPackages() = this == UserPath
}

/** The registry values ketch wrote that are present now. Always empty off Windows. */
fun registryEntries(cfg: Config): List<RegistryEntry> =
    listOfNotNull(
        RegistryEntry.UserPath.takeIf { userPathConfigured(cfg) },
        RegistryEntry.CmdMacros.takeIf { cmdMacrosConfigured(cfg) },
    )

/** Remove one registry value ketch wrote. */
fun removeRegistryEntry(
    cfg: Config,
    entry: RegistryEntry,
) {
    when (entry) {
        RegistryEntry.UserPath -> uninstallUser(cfg, dryRun = false)
        RegistryEntry.CmdMacros -> uninstallCmdMacros(cfg)
    }
}

/**
 * A `ketch doctor` warning for user PATH entries that point into a ketch root that is gone: left by an uninstall that
 * could not reach the registry, or by a root deleted by hand. Null when there are none, and always off Windows.
 */
fun staleRegistryCheck(cfg: Config): DoctorCheck? {
    if (!HOST_WINDOWS) return null
    val stale = runCatching { stalePathEntries(readUserPath(), cfg.binDir) }.getOrDefault(emptyList())
    return stale.takeIf { it.isNotEmpty() }?.let {
        DoctorCheck.warn(
            "user PATH",
            "names ketch folders that no longer exist: ${it.joinToString(", ")}",
            "Remove them from the user PATH in System Properties → Environment Variables.",
        )
    }
}

/** Take the ketch block out of one PowerShell profile. True when it changed. A profile left empty goes, since ketch created it. */
fun uninstallPowershellProfile(file: Path): Boolean =
    when (val edit = unspliceProfile(read(file))) {
        null -> {
            false
        }

        is ProfileEdit.Rewrite -> {
            write(file, edit.text)
            true
        }

        ProfileEdit.Delete -> {
            val target = canon(file)
            try {
                removeFile(target)
            } catch (e: FsException) {
                throw KetchError.fsKit(target, e)
            }
            true
        }
    }

/** The macros cmd.exe gets. cmd has no programmable completion, so short names for the common commands are what it can have. */
const val DOSKEY_MACROS = "ki=ketch install $*\r\nku=ketch upgrade $*\r\nkl=ketch list $*\r\nkun=ketch uninstall $*\r\n"

/** Where the macro file lives: in the root, not the versioned store prefix, so the AutoRun line naming it survives every `self upgrade`. */
fun doskeyFile(cfg: Config) = joinPath(joinPath(joinPath(cfg.root, "share"), "ketch"), "ketch.doskey")

/**
 * The command ketch adds to AutoRun to load [file].
 *
 * cmd expands `%…%` in AutoRun and has no escape for `"` inside quotes, so a path holding either is refused rather
 * than written and hoped for.
 */
internal fun autorunCommand(file: Path): String {
    val path = nativeSpelling(file)
    if (path.any { it in "\"%\n\r" }) throw KetchError.Msg("$path holds a character cmd's AutoRun cannot quote")
    return "doskey /macrofile=\"$path\""
}

/** What to do with the AutoRun value. */
internal sealed interface AutoRunEdit {
    /** Leave it as it is. */
    data object Unchanged : AutoRunEdit

    /** Write this value. */
    data class Set(
        val value: String,
    ) : AutoRunEdit

    /** Delete the value: nothing but ketch's command was in it. */
    data object Delete : AutoRunEdit
}

private fun autorunHas(
    current: String,
    ours: String,
) = current == ours || current.endsWith(" & $ours") || current.startsWith("$ours & ") || " & $ours & " in current

/**
 * Add [ours] to an AutoRun value, after whatever the user already runs there. Appending exactly ` & ours` is what lets
 * [autorunRemove] give the earlier value back byte for byte.
 */
internal fun autorunAdd(
    current: String?,
    ours: String,
): AutoRunEdit =
    when {
        current != null && autorunHas(current, ours) -> AutoRunEdit.Unchanged

        // A blank value runs nothing; `  & cmd` would be a syntax error.
        current != null && current.isNotBlank() -> AutoRunEdit.Set("$current & $ours")

        else -> AutoRunEdit.Set(ours)
    }

/** Take [ours] back out of an AutoRun value, leaving the rest as it was. */
internal fun autorunRemove(
    current: String,
    ours: String,
): AutoRunEdit {
    val middle = " & $ours & "
    val at = current.indexOf(middle)
    return when {
        current == ours -> AutoRunEdit.Delete
        current.endsWith(" & $ours") -> AutoRunEdit.Set(current.removeSuffix(" & $ours"))
        at >= 0 -> AutoRunEdit.Set("${current.substring(0, at)} & ${current.substring(at + middle.length)}")
        current.startsWith("$ours & ") -> AutoRunEdit.Set(current.removePrefix("$ours & "))
        else -> AutoRunEdit.Unchanged
    }
}

/** Write the doskey macro file and load it from cmd's AutoRun, after whatever AutoRun already runs. */
fun installCmdMacros(cfg: Config): Outcome {
    val file = doskeyFile(cfg)
    val ours = autorunCommand(file)
    try {
        WriteOptions(createParent = true).write(file, DOSKEY_MACROS.encodeToByteArray())
    } catch (e: FsException) {
        throw KetchError.fsKit(file, e)
    }
    val current = readAutorun()
    val edit = autorunAdd(current?.second, ours)
    if (edit !is AutoRunEdit.Set) return Outcome.Unchanged
    // An `ExpandString` stays one: its `%VAR%` must keep expanding.
    writeAutorun(current?.first ?: "String", edit.value)
    return Outcome.Added
}

/** True when cmd's AutoRun loads this root's macro file. Always false off Windows. */
fun cmdMacrosConfigured(cfg: Config): Boolean {
    val ours = runCatching { autorunCommand(doskeyFile(cfg)) }.getOrNull()
    return HOST_WINDOWS && ours != null && runCatching { readAutorun() }.getOrNull()?.let { autorunHas(it.second, ours) } == true
}

/**
 * Take this root's command back out of AutoRun (the earlier value comes back as it was, or the value goes when it held
 * only ketch's) and delete the macro file. True when AutoRun changed, which off Windows it never does; the file is
 * still removed there, since a root copied from Windows may carry one.
 */
fun uninstallCmdMacros(cfg: Config): Boolean {
    val file = doskeyFile(cfg)
    val changed = HOST_WINDOWS && takeOutOfAutorun(autorunCommand(file))
    try {
        if (entryKind(file) != null) removeFile(file)
    } catch (e: FsException) {
        throw KetchError.fsKit(file, e)
    }
    // Only if empty: `share` is a name a user could have put there too.
    var dir = file.parent
    while (dir != null && dir != cfg.root && runCatching { SystemFileSystem.delete(dir) }.isSuccess) dir = dir.parent
    return changed
}

private fun takeOutOfAutorun(ours: String): Boolean {
    val (kind, value) = readAutorun() ?: return false
    return when (val edit = autorunRemove(value, ours)) {
        AutoRunEdit.Unchanged -> false
        is AutoRunEdit.Set -> true.also { writeAutorun(kind, edit.value) }
        AutoRunEdit.Delete -> true.also { deleteAutorun() }
    }
}
