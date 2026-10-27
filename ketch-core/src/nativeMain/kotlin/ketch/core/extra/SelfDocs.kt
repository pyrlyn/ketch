package ketch.core.extra

import fs.FsException
import fs.atomic
import fs.createDirAll
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.model.CompletionShell
import ketch.core.model.ExtraKind
import ketch.core.model.ExtraPath
import ketch.core.model.ExtraPathSpec
import kotlinx.io.files.Path

/**
 * ketch's own man pages and completion scripts. Both come from the command-line definition, which belongs to the
 * binary; the binary hands the renderers in so the core never depends on the CLI.
 */
class SelfDocs(
    /** Writes every man page into the given directory, creating it, and returns the paths it wrote. */
    val manPages: (Path) -> List<Path>,
    /** One shell's completion script, byte for byte what `ketch completions` prints for it. */
    val completion: (CompletionShell) -> ByteArray,
)

/**
 * Write ketch's own man pages and completion scripts into [prefix]. Generated under the store so the links that
 * follow have the same ownership proof as binaries: they point at files ketch placed.
 */
fun writeKetchDocs(
    prefix: Path,
    docs: SelfDocs,
): List<ExtraPath> {
    val extras = writeKetchManPages(prefix, docs).toMutableList()
    for (shell in CompletionShell.entries) {
        val rel = generatedCompletionRel(shell)
        val path = Path(prefix, rel)
        val parent = path.parent ?: prefix
        fsKit(parent) { createDirAll(parent) }
        fsKit(path) { atomic(path, docs.completion(shell)) }
        extras += ExtraPath.Spec(ExtraPathSpec(path = rel, kind = ExtraKind.Completion, shell = shell))
    }
    return extras
}

/** Every page [docs] renders, under `share/man/man1/`, recorded as a man extra so uninstall and relink take each one back. */
private fun writeKetchManPages(
    prefix: Path,
    docs: SelfDocs,
): List<ExtraPath> {
    val dirRel = "share/man/man1"
    return docs.manPages(Path(prefix, dirRel)).map { ExtraPath.Plain("$dirRel/${it.name}") }
}

private inline fun fsKit(
    path: Path,
    body: () -> Unit,
) = try {
    body()
} catch (e: FsException) {
    throw KetchError.fsKit(path, e)
}
