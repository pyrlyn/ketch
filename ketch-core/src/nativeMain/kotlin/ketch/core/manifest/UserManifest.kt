// The one place a user manifest is written: `writeBins` records the binaries a package links, `writeManifest`
// stores what `ketch import` generated. The TOML itself is read, rendered and edited by `tomlfile`.
package ketch.core.manifest

import fs.FsException
import fs.WriteOptions
import fs.canonicalize
import fs.createDirAll
import fs.joinPath
import fs.readText
import ketch.core.config.Config
import ketch.core.config.sanitizeComponent
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.fsKitAt
import ketch.core.model.Manifest
import ketch.core.model.normalizeName
import ketch.core.tomlfile.EditDocument
import ketch.core.tomlfile.render
import kotlinx.io.files.Path

/** `0600`: a manifest ketch creates is private, as the temporary file it used to be built from was. */
private const val NEW_MANIFEST_MODE = 0b110_000_000

/** Where `ketch edit`/`ketch pin` should write a manifest for this package. */
fun userManifestPath(
    cfg: Config,
    name: String,
): Path = joinPath(cfg.manifestDir, "${sanitizeComponent(name)}.toml")

/** Serialise a manifest for a user manifest file. */
fun toToml(manifest: Manifest): String = render(manifest, "manifest")

/**
 * Write `bin = [{ name = "<a>" }, { name = "<b>" }, …]` for [packageName] into the user manifest at [path], one entry
 * per name in [bins], changing nothing else in the file. False when the package there already has a `bin`: a choice
 * is only ever written where none was made.
 *
 * The file is the user's, so comments, order and spacing survive: [EditDocument] changes the one key and renders the
 * rest back as it was read. The result is parsed as a manifest again before it replaces the file, so a write that
 * would leave it unloadable fails instead.
 */
fun writeBins(
    path: Path,
    packageName: String,
    bins: List<String>,
): Boolean {
    // A manifest linked in from a dotfiles repository is edited there, not replaced by a copy that silently stops
    // following the repository.
    val target = fsKitAt(path) { canonicalize(path) }
    val label = target.toString()
    val doc = EditDocument.parse(fsKitAt(target) { readText(target) }, label)
    val wanted = normalizeName(packageName)
    val table =
        doc.packageTable { normalizeName(it) == wanted }
            ?: throw KetchError.Msg("$label has no `[[package]]` table named `$packageName` to write `bin` into")
    if (table.containsKey("bin")) return false
    table.setInlineTables("bin", "name", bins)
    val body = doc.render()
    parseRegistry(body, label)
    replaceFile(target, body)
    return true
}

/**
 * Write a whole user manifest that `ketch import` generated, creating the manifest directory on first use. The text
 * is parsed as a manifest first, so a converter bug fails here instead of leaving a file every later command warns
 * about; a file linked from elsewhere is written through.
 */
fun writeManifest(
    path: Path,
    text: String,
) {
    val target =
        try {
            canonicalize(path)
        } catch (e: FsException) {
            if (e.kind != FsException.Kind.NotFound) throw KetchError.fsKit(path, e)
            path
        }
    parseRegistry(text, target.toString())
    target.parent?.let { dir -> fsKitAt(dir) { createDirAll(dir) } }
    replaceFile(target, text)
}

/** Replace [target] by renaming a finished copy over it, so an interrupted write leaves the old file whole. */
internal fun replaceFile(
    target: Path,
    body: String,
) = fsKitAt(target) {
    // An existing manifest keeps the mode the user gave it; a new one is private.
    WriteOptions(followSymlinks = false, mode = NEW_MANIFEST_MODE).write(target, body.encodeToByteArray())
}
