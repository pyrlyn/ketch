// Putting the ketch bin directory on PATH.
//
// On Unix that means a block in bash, zsh or fish startup files. This is the only code in ketch that writes outside
// the ketch root, and it runs only when the user asks for it: `ketch path install`, or `ketch doctor --fix`.
// Everything it adds sits between two markers so it can be found again, rewritten in place, and taken back out
// without guessing.
package ketch.core.shell

import fs.EntryKind
import fs.canon
import fs.entryKind
import fs.joinPath
import ketch.core.config.envOs
import ketch.core.model.Os
import ketch.core.model.TargetSpec
import ketch.core.model.host
import ketch.core.text.asciiLowercase
import kotlinx.io.files.Path

/**
 * A shell whose PATH ketch knows how to set up.
 *
 * Anything else is handled by printing the line to add by hand: a shell whose quoting rules are not implemented here
 * would be edited wrongly, and a broken startup file costs the user more than a manual paste.
 */
enum class Shell(
    /** The name the user types, and the one `$SHELL` ends with. */
    val shellName: String,
) {
    Bash("bash"),
    Zsh("zsh"),
    Fish("fish"),
    ;

    /**
     * Files this shell may already be configured in, most preferred first.
     *
     * bash is the awkward one: a terminal on macOS starts a login shell, which reads `.bash_profile` and never
     * `.bashrc`, while most Linux terminals do the reverse. Ordering by what the host actually starts is what stops
     * ketch writing into a file nothing reads.
     */
    internal fun candidates(home: Path): List<Path> =
        when (this) {
            Bash -> {
                val files = listOf(".bash_profile", ".bashrc")
                (if (TargetSpec.host().os == Os.MacOs) files else files.reversed()).map { joinPath(home, it) }
            }

            Zsh -> {
                listOf(joinPath(zdotdir(home), ".zshrc"))
            }

            Fish -> {
                listOf(joinPath(joinPath(configHome(home), "fish"), "config.fish"))
            }
        }

    /** The file to edit: the first candidate that already exists, else the one this host would create. */
    fun configFile(home: Path): Path {
        val candidates = candidates(home)
        return candidates.firstOrNull(::isFile) ?: candidates.first()
    }

    /** The one line that does the work. */
    internal fun export(binDir: String): String =
        when (this) {
            // A single-quoted literal joined to "$PATH" keeps every character of the directory: a path holding a
            // space, a `$` or a quote still expands to exactly itself.
            Bash, Zsh -> "export PATH=${quotePosix(binDir)}:\"\$PATH\""

            // Deliberately not `fish_add_path`: that writes a universal variable, which outlives the file ketch is
            // editing and would survive `ketch path uninstall`.
            Fish -> "set -gx PATH ${quoteFish(binDir)} \$PATH"
        }

    /** The whole block ketch owns, markers included. */
    internal fun block(binDir: String) = "$BEGIN\n${export(binDir)}\n$END\n"

    companion object {
        /** Every shell ketch can configure. */
        val ALL = entries.toList()

        private const val EXE = ".exe"

        /**
         * The shell a program path refers to, or null for one ketch cannot set up.
         *
         * Both the directory and the leading `-` that marks a login shell are ignored, because `$SHELL` and `argv[0]`
         * disagree about both. A Windows `bash.exe` (and a `C:\…\bash.exe` path) must still resolve: Git for Windows
         * puts `.exe` on `$SHELL` and `argv[0]`, in whatever case PATH spelled it.
         */
        fun fromProgram(program: String): Shell? {
            val stem = program.substringAfterLast('/').substringAfterLast('\\').trimStart('-')
            val bare =
                if (stem.length >= EXE.length && stem.takeLast(EXE.length).asciiLowercase() == EXE) {
                    stem.dropLast(EXE.length)
                } else {
                    stem
                }
            return ALL.firstOrNull { it.shellName == bare.asciiLowercase() }
        }
    }
}

/** Rust `Path::is_file`: a regular file, through any link. */
internal fun isFile(path: Path) = entryKind(canon(path)) == EntryKind.File

/** An absolute path from [key], as Rust's `var_os(..).filter(is_absolute)` takes one. */
private fun absoluteEnv(key: String) = envOs(key)?.takeIf { it.startsWith('/') }?.let(::Path)

/** zsh reads its files from `$ZDOTDIR` when that is set, and only falls back to the home directory when it is not. */
private fun zdotdir(home: Path) = absoluteEnv("ZDOTDIR") ?: home

private fun configHome(home: Path) = absoluteEnv("XDG_CONFIG_HOME") ?: joinPath(home, ".config")
