// The Windows side of PATH and completion setup: the user environment, the PowerShell profiles and cmd's doskey
// macros. Off Windows there is no registry ketch writes and no Documents folder holding a profile, so these answer
// "nothing here" and only the file cleanup is real. The Windows backend arrives with the Windows port (T13).
package ketch.core.shell

import fs.FsException
import fs.canon
import fs.entryKind
import fs.removeFile
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.platform.DoctorCheck
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * True when the Windows user PATH already names the bin dir.
 *
 * Off Windows this is always false: there is no user environment ketch owns.
 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
fun userPathConfigured(cfg: Config): Boolean = false

/** [Status.userPath]: null off Windows, where there is no user PATH to report on. */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun userPathOrNull(cfg: Config): Boolean? = null

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

/** A `ketch doctor` warning for user PATH entries that point into a ketch root that is gone; always null off Windows. */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
fun staleRegistryCheck(cfg: Config): DoctorCheck? = null

/** Every PowerShell profile holding a ketch block. Empty off Windows. */
fun powershellProfilesWithBlock(): List<Path> = emptyList()

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
fun doskeyFile(cfg: Config) = Path(Path(Path(cfg.root, "share"), "ketch"), "ketch.doskey")

/** True when cmd's AutoRun loads this root's macro file. Always false off Windows. */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
fun cmdMacrosConfigured(cfg: Config): Boolean = false

/**
 * Take this root's command back out of AutoRun and delete the macro file. True when AutoRun changed, which off Windows
 * it never does; the file is still removed, since a root copied from Windows may carry one.
 */
fun uninstallCmdMacros(cfg: Config): Boolean {
    val file = doskeyFile(cfg)
    try {
        if (entryKind(file) != null) removeFile(file)
    } catch (e: FsException) {
        throw KetchError.fsKit(file, e)
    }
    // Only if empty: `share` is a name a user could have put there too.
    var dir = file.parent
    while (dir != null && dir != cfg.root && runCatching { SystemFileSystem.delete(dir) }.isSuccess) dir = dir.parent
    return false
}
