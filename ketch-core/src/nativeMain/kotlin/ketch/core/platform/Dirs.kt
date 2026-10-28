package ketch.core.platform

import fs.FsException
import fs.createNew
import fs.entryKind
import fs.removeFile
import ketch.core.config.HOST_WINDOWS
import ketch.core.config.envOs
import ketch.core.config.homeDir
import ketch.core.extra.ExtraPlacement
import ketch.core.model.CompletionShell
import ketch.core.model.LinkRecord
import ketch.core.model.rustComponents
import ketch.core.model.rustComponentsFor
import ketch.core.text.eqIgnoreAsciiCase
import kotlinx.io.files.Path
import kotlin.random.Random

private fun xdg(
    key: String,
    fallback: String,
): Path = envOs(key)?.takeIf { it.isNotEmpty() }?.let(::Path) ?: Path(homeDir() ?: Path("."), fallback)

/** `$XDG_DATA_HOME`, falling back to `~/.local/share` on every OS. */
fun dataHome(): Path = xdg("XDG_DATA_HOME", ".local/share")

/** `$XDG_CONFIG_HOME`, falling back to `~/.config`. */
fun configHome(): Path = xdg("XDG_CONFIG_HOME", ".config")

/**
 * True when [path] is [root] or a descendant of it, component by component as Rust's `Path::starts_with` compares.
 * Windows also folds ASCII case: a store root typed `C:\Users\IVAN\…` and a payload recorded as `C:\Users\ivan\…` must
 * still count as inside, or uninstall refuses to delete the files and leaves orphans forever.
 */
fun pathIsWithin(
    path: Path,
    root: Path,
) = pathIsWithinFor(path.toString(), root.toString(), HOST_WINDOWS)

/** Like [pathIsWithin], but false when [path] and [root] are the same. */
fun pathIsStrictWithin(
    path: Path,
    root: Path,
) = pathIsStrictWithinFor(path.toString(), root.toString(), HOST_WINDOWS)

/** [pathIsWithin] with the Windows rules switched on or off, so both are tested on every host. */
internal fun pathIsWithinFor(
    path: String,
    root: String,
    windows: Boolean,
): Boolean {
    if (root.isEmpty()) return false
    val parts = rustComponentsFor(root, windows)
    return sameParts(rustComponentsFor(path, windows).take(parts.size), parts, windows)
}

internal fun pathIsStrictWithinFor(
    path: String,
    root: String,
    windows: Boolean,
) = pathIsWithinFor(path, root, windows) && !sameParts(rustComponentsFor(path, windows), rustComponentsFor(root, windows), windows)

private fun sameParts(
    a: List<String>,
    b: List<String>,
    windows: Boolean,
) = a == b || (windows && a.size == b.size && a.zip(b).all { (x, y) -> x.eqIgnoreAsciiCase(y) })

/** Unix-style completion directories. */
fun completionDirFor(shell: CompletionShell): Path =
    when (shell) {
        CompletionShell.Bash -> Path(dataHome(), "bash-completion/completions")
        CompletionShell.Zsh -> Path(dataHome(), "zsh/site-functions")
        CompletionShell.Fish -> Path(configHome(), "fish/completions")
        CompletionShell.Elvish -> Path(configHome(), "elvish/lib")
        CompletionShell.Powershell -> Path(configHome(), "powershell/Completions")
    }

/** Doctor lines for the destinations `place` will write. Missing directories are reported as ok: install creates them rather than surprising the user with a write they were not shown. */
fun extraDestinationChecks(platform: Platform): List<DoctorCheck> =
    listOf(destCheck("man", platform.userManRoot(), mentionManpath = true)) +
        CompletionShell.entries.map { destCheck("completions-${it.asStr}", platform.completionDir(it), mentionManpath = false) }

private fun destCheck(
    name: String,
    path: Path,
    mentionManpath: Boolean,
): DoctorCheck {
    val exists = entryKind(path) != null
    if (exists && probeWritable(path) != null) return DoctorCheck.fail(name, "$path is not writable", "chmod u+w $path")
    val detail =
        buildString {
            append(path)
            if (!exists) append(" — created on install")
            if (mentionManpath) append("; add this directory to MANPATH so `man` finds pages ketch installs")
        }
    return DoctorCheck.ok(name, detail)
}

/** Create and remove a file in [dir], as Rust's `tempfile_in` probe does; the error's text, or null when it worked. */
internal fun probeWritable(dir: Path): String? =
    try {
        val probe = Path(dir, ".ketch-probe" + Random.nextLong().toULong().toString(PROBE_RADIX))
        createNew(probe, ByteArray(0))
        removeFile(probe)
        null
    } catch (e: FsException) {
        e.withoutPath().message ?: e.toString()
    }

private const val PROBE_RADIX = 36

/** Link classified extras from [root] (the store prefix) to their destinations; on Windows, copy them. */
fun exposeExtras(
    extras: List<ExtraPlacement>,
    root: Path,
    owned: Path,
    recorded: List<LinkRecord>,
): List<LinkRecord> =
    withClaims {
        if (HOST_WINDOWS) linkPlannedWindowsExtras(extras, root, recorded, it) else linkPlannedExtras(extras, root, owned, recorded, it)
    }
