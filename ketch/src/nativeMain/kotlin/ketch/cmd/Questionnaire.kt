// Port of cmd/config.rs: `ketch config create`, the questionnaire that writes a package file, and `ketch config reset`,
// which puts ketch's own config back to the compiled defaults. Every rule about what an answer means lives in
// ketch.core.wizard, where it is testable without a terminal; this file only asks, previews and writes.
package ketch.cmd

import cli.Matches
import fs.atomic
import fs.backup
import fs.createNew
import ketch.core.config.Config
import ketch.core.config.currentDir
import ketch.core.config.sanitizeComponent
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.error.ioAt
import ketch.core.model.BinSpec
import ketch.core.model.PackageKind
import ketch.core.model.PackageRef
import ketch.core.model.normalizeName
import ketch.core.model.notAReference
import ketch.core.registry.PACKAGE_FILE
import ketch.core.text.rustTrim
import ketch.core.wizard.Answers
import ketch.core.wizard.manifest
import ketch.core.wizard.parseKind
import ketch.core.wizard.parseStripPrefix
import ketch.core.wizard.render
import ketch.core.wizard.splitList
import ketch.ui.confirm
import ketch.ui.note
import ketch.ui.out
import ketch.ui.prompt
import ketch.ui.promptRequired
import ketch.ui.question
import ketch.ui.success
import ketch.ui.warn
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Run the selected `ketch config` operation. */
fun config(
    cfg: Config,
    args: Matches,
) {
    val command = checkNotNull(args.subcommand)
    val sub = command.matches
    when (command.name) {
        // The questionnaire writes into the working tree, never the ketch root, so [cfg] plays no part in it.
        "create" -> create(sub.value("file")?.let(::Path), sub.flag("force"), sub.flag("yes"))

        else -> reset(cfg, sub.flag("yes"))
    }
}

/**
 * Write `config.toml` in the ketch root with the compiled defaults. The existing file is backed up beside itself
 * first, unless it is missing or already byte-identical to a sibling backup.
 */
private fun reset(
    cfg: Config,
    yes: Boolean,
) {
    if (!yes && !confirm("reset config.toml to the compiled defaults?", false)) return
    fsKitAt(cfg.configFile) { backup(cfg.configFile) }?.let { note("backed up to $it") }
    ioAt(cfg.configFile) { atomic(cfg.configFile, Config.defaultToml().encodeToByteArray()) }
    success("reset", cfg.configFile.toString())
}

private fun create(
    file: Path?,
    force: Boolean,
    yes: Boolean,
) {
    val path = file ?: Path(PACKAGE_FILE)
    // Refuse before the first question: a questionnaire that asks everything and then declines to write is a waste
    // of the answers.
    if (SystemFileSystem.exists(path) && !force) throw KetchError.Msg("$path already exists; pass --force to replace it")
    val manifest = manifest(askEverything())
    val body = render(manifest)
    note("$path will say:")
    // The body ends with its own newline and `out` adds one; without the trim the preview grows a blank line the
    // written file does not have.
    out(body.trimEnd('\n'))
    if (!yes && !confirm("write $path?", false)) return
    writeManifest(path, body, force)
    success("wrote", path.toString())
    note("try it with `ketch install ${manifest.name} --verbose`, offer it with `ketch registry push`")
}

/** Write a generated manifest, refusing a late overwrite unless forced. */
internal fun writeManifest(
    path: Path,
    body: String,
    force: Boolean,
) = ioAt(path) {
    if (force) atomic(path, body.encodeToByteArray()) else createNew(path, body.encodeToByteArray())
}

/** The questionnaire, one field per question, in the order the schema documents them. */
private fun askEverything(): Answers {
    val source = askSource()
    val name = askName()
    val description = askText("one-line description?")
    val homepage = askText("homepage URL?")
    val kind = askKind()
    return Answers(
        source = source,
        name = name,
        description = description,
        homepage = homepage,
        kind = kind,
        prerelease = question("consider prereleases when resolving `latest`?", false),
        stripPrefix = askStripPrefix(),
        provides = askList("other names this package answers to, comma-separated?"),
        notes = askText("notes to print after a successful install?"),
        bin = askBins(name, kind),
        extraPaths = askList("extra files to record, comma-separated (man pages, completions)?"),
        assetInclude = askList("release assets must match one of, comma-separated globs?"),
        assetExclude = askList("release assets must never match, comma-separated globs?"),
        assetTarget = askAssetTargets(),
    )
}

/** The one field with no default and no way to guess. A typo is re-asked, so one slip does not restart everything. */
private fun askSource(): String {
    while (true) {
        val answer = promptRequired("source: `owner/repo`, or `scheme:id` for a plugin?")
        PackageRef.parse(answer)?.let { return it.toString() }
        warn(notAReference(answer))
    }
}

/**
 * The install name, defaulting to the current directory spelled like a package name. A name that is not one path
 * component as written is re-asked: `Manifest.validate` would refuse the manifest it produces.
 */
private fun askName(): String {
    val default = defaultName()
    while (true) {
        val answer = prompt("package name?", default)
        if (sanitizeComponent(answer) == answer) return answer
        warn("`$answer` cannot be the package name: it would install somewhere other than where it says")
    }
}

/** Sanitized as well as normalized: an empty answer returns the default, so a default [askName] rejects would be offered back forever. */
private fun defaultName(): String {
    val folder =
        currentDir()?.name?.takeIf { it.isNotEmpty() }
            ?: throw KetchError.Msg("the current directory has no name a package name could come from")
    return sanitizeComponent(normalizeName(folder))
}

private fun askKind(): PackageKind {
    while (true) {
        parseKind(prompt("kind of payload: auto, binary or app?", "auto"))?.let { return it }
        warn("kind is auto, binary or app")
    }
}

private fun askStripPrefix(): ULong? {
    while (true) {
        try {
            return parseStripPrefix(prompt("leading wrapper directories to strip when extracting (0-8)?", "0"))
        } catch (e: KetchError) {
            warn(e.message)
        }
    }
}

/**
 * `bin` entries. A package that links binaries names the command it puts on PATH, so its first entry is asked
 * outright rather than offered; more follow until the user stops adding them. An `app` links none and is only
 * offered them.
 */
private fun askBins(
    pkg: String,
    kind: PackageKind,
): List<BinSpec> {
    val bins = mutableListOf<BinSpec>()
    // The name defaults to something non-empty, so an empty answer or the end of piped input settles this at once.
    while (kind != PackageKind.App && bins.isEmpty()) {
        val bin = askBin(pkg)
        if (bin?.name != null) bins += bin else warn("the first `bin` entry names the binary users run")
    }
    val offer = if (bins.isEmpty()) "add a `bin` entry?" else "add another `bin` entry?"
    while (question(offer, false)) askBin(pkg)?.let { bins += it }
    return bins
}

/**
 * One `bin` entry. The binary name defaults to the file the path glob points at, else the package name. An entry
 * with neither a path nor a name says nothing, so it is refused.
 */
private fun askBin(pkg: String): BinSpec? {
    val path = askText("path glob inside the payload?")
    val linkDefault = path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: pkg
    val name = prompt("binary name, the command on PATH?", linkDefault)
    if (path == null && name.isEmpty()) {
        warn("a `bin` entry needs a path, a name, or both")
        return null
    }
    return BinSpec(path = path, name = name.ifEmpty { null })
}

/** Per-target asset overrides, one `target=glob` pair per line, until an empty line ends the loop. */
private fun askAssetTargets(): Map<String, String> {
    val overrides = mutableMapOf<String, String>()
    while (true) {
        val answer = prompt("asset override for one target, as `target=glob` (empty line to finish)?", "")
        if (answer.isEmpty()) return overrides.entries.sortedBy { it.key }.associate { it.key to it.value }
        val target = answer.substringBefore('=', "").rustTrim()
        val glob = answer.substringAfter('=', "").rustTrim()
        if (target.isNotEmpty() && glob.isNotEmpty()) {
            overrides[target] = glob
        } else {
            warn("an override looks like `macos-aarch64=*-aarch64-apple-darwin.tar.gz`")
        }
    }
}

private fun askList(question: String): List<String> = splitList(prompt(question, ""))

/** An optional line: an empty answer means the field is simply absent. */
private fun askText(question: String): String? = prompt(question, "").ifEmpty { null }
