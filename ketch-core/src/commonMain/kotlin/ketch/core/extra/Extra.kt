package ketch.core.extra

import ketch.core.error.KetchError
import ketch.core.extract.safeMemberPath
import ketch.core.model.ClassifiedExtra
import ketch.core.model.CompletionShell
import ketch.core.model.ExtraKind
import ketch.core.model.ExtraPath
import ketch.core.model.ExtraPathSpec
import ketch.core.model.LinkRole
import ketch.core.text.asciiLowercase
import kotlinx.io.files.Path

/*
 * Man pages and shell completions declared in `extra_paths`. Classification is the trust boundary: a payload path
 * becomes a man page or a completion only from explicit metadata or the documented path rules, and an ambiguous
 * entry is refused rather than guessed. Destination directories come from the platform; this only maps a
 * classified entry onto them.
 */

/** Classify every extra path and resolve its destination. Does not touch disk. */
fun plan(
    extras: List<ExtraPath>,
    manRoot: Path,
    completionDir: (CompletionShell) -> Path,
): List<ExtraPlacement> {
    val dests = mutableSetOf<String>()
    return extras.map { entry ->
        val classified = classify(entry)
        val dest = destination(classified, manRoot, completionDir)
        if (!dests.add(dest.toString())) throw KetchError.Msg("multiple extra_paths want to create $dest")
        val role = if (classified.kind == ExtraKind.Man) LinkRole.Man else LinkRole.Completion
        ExtraPlacement(classified.relPath, dest, role)
    }
}

/** Classify one `extra_paths` entry. */
fun classify(entry: ExtraPath): ClassifiedExtra =
    when (entry) {
        is ExtraPath.Plain -> classifyPath(entry.relPath)
        is ExtraPath.Spec -> classifySpec(entry.spec)
    }

/** Where `ketch`'s own generated completion script for [shell] sits, relative to its prefix. */
internal fun generatedCompletionRel(shell: CompletionShell): String =
    "share/ketch/completions/" +
        when (shell) {
            CompletionShell.Bash -> "ketch"
            CompletionShell.Zsh -> "_ketch"
            CompletionShell.Fish -> "ketch.fish"
            CompletionShell.Elvish -> "ketch.elv"
            CompletionShell.Powershell -> "ketch.ps1"
        }

internal fun contained(path: String): String =
    try {
        safeMemberPath(path)
    } catch (e: KetchError) {
        throw KetchError.Msg("extra path `$path` must stay inside the package", e)
    }

private fun classifyPath(path: String): ClassifiedExtra {
    contained(path)
    val components = pathComponents(path)
    val basename = fileName(path)
    val man = if (hasManDir(components)) manSectionFromBasename(basename) else null
    val shell = if (hasCompletionDir(components)) completionShellFromBasename(basename, components) else null
    return when {
        man != null && shell == null -> ClassifiedExtra(path, ExtraKind.Man, null, man)

        man == null && shell != null -> ClassifiedExtra(path, ExtraKind.Completion, shell, null)

        man != null -> throw KetchError.Msg("extra path `$path` looks like both a man page and a completion; set `kind` explicitly")

        else -> throw KetchError.Msg(
            "extra path `$path` is not a man page or a completion; use `{ path = \"$path\", kind = \"man\" }` or " +
                "`{ path = \"$path\", kind = \"completion\", shell = \"...\" }`",
        )
    }
}

private fun classifySpec(spec: ExtraPathSpec): ClassifiedExtra {
    contained(spec.path)
    val basename = fileName(spec.path)
    return when (spec.kind) {
        ExtraKind.Man -> {
            ClassifiedExtra(spec.path, ExtraKind.Man, null, manSection(spec, basename))
        }

        ExtraKind.Completion -> {
            val shell =
                spec.shell ?: completionShellFromBasename(basename, pathComponents(spec.path))
                    ?: throw KetchError.Msg("extra path `${spec.path}` needs `shell` (bash, zsh, fish, elvish, powershell)")
            ClassifiedExtra(spec.path, ExtraKind.Completion, shell, null)
        }
    }
}

private fun manSection(
    spec: ExtraPathSpec,
    basename: String,
): String {
    val section = spec.section ?: manSectionFromBasename(basename)
    val refusal =
        when {
            section == null -> "extra path `${spec.path}` needs `section` (for example `1`)"
            !manSectionOk(section) -> "extra path `${spec.path}` has unusable man section `$section`"
            else -> null
        }
    if (refusal != null) throw KetchError.Msg(refusal)
    return checkNotNull(section)
}

private fun destination(
    classified: ClassifiedExtra,
    manRoot: Path,
    completionDir: (CompletionShell) -> Path,
): Path {
    val basename = fileName(classified.relPath)
    return when (classified.kind) {
        ExtraKind.Man -> {
            val section =
                classified.section ?: throw KetchError.Msg("extra path `${classified.relPath}` is a man page without a section")
            Path(manRoot, "man$section", basename)
        }

        ExtraKind.Completion -> {
            val shell =
                classified.shell ?: throw KetchError.Msg("extra path `${classified.relPath}` is a completion without a shell")
            Path(completionDir(shell), completionDestName(basename, shell))
        }
    }
}

private fun completionDestName(
    basename: String,
    shell: CompletionShell,
): String =
    when {
        shell == CompletionShell.Bash && basename.endsWith(".bash") -> basename.removeSuffix(".bash")
        shell == CompletionShell.Zsh && !basename.startsWith('_') -> "_$basename"
        else -> basename
    }

private fun pathComponents(path: String): List<String> = path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.map { it.asciiLowercase() }

private fun fileName(path: String): String =
    path.split('/').lastOrNull { it.isNotEmpty() && it != "." }?.takeIf { it != ".." }
        ?: throw KetchError.Msg("extra path `$path` has no file name")

private val COMPLETION_DIRS = setOf("complete", "completions", "completion", "bash-completion", "site-functions")

private fun hasCompletionDir(components: List<String>) = components.any { it in COMPLETION_DIRS }

private fun hasManDir(components: List<String>) = components.any { it == "man" || it == "doc" || it == "docs" || isManNDir(it) }

private fun isManNDir(c: String): Boolean = c.startsWith("man") && c.length > "man".length && manSectionOk(c.removePrefix("man"))

private fun manSectionOk(section: String): Boolean = section.isNotEmpty() && section[0] in '1'..'9' && section.drop(1).all { it in 'a'..'z' }

private fun manSectionFromBasename(basename: String): String? {
    val name = basename.removeSuffix(".gz")
    val dot = name.lastIndexOf('.')
    val section = if (dot > 0) name.substring(dot + 1) else null
    return section?.takeIf(::manSectionOk)
}

private val SHELL_SUFFIXES =
    listOf(
        ".bash" to CompletionShell.Bash,
        ".zsh" to CompletionShell.Zsh,
        ".fish" to CompletionShell.Fish,
        ".ps1" to CompletionShell.Powershell,
        ".elv" to CompletionShell.Elvish,
    )

private fun completionShellFromBasename(
    name: String,
    components: List<String>,
): CompletionShell? {
    val lower = name.asciiLowercase()
    val plain = '.' !in name
    return SHELL_SUFFIXES.firstOrNull { lower.endsWith(it.first) }?.second
        ?: when {
            name.startsWith('_') && '.' !in name.drop(1) -> CompletionShell.Zsh
            plain && "bash-completion" in components -> CompletionShell.Bash
            plain && "site-functions" in components -> CompletionShell.Zsh
            else -> null
        }
}
