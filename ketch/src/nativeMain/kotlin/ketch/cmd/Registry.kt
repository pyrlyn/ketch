// Port of cmd/registry.rs: `validate` is the fail-closed check registry CI runs over every package folder, `status`
// reports the local copy's age and source with no network, and `push` puts a project's `ketch.toml` in front of the
// people who curate the registry.
package ketch.cmd

import cli.Matches
import fs.TempDir
import fs.atomic
import fs.isDir
import ketch.core.changelog.sanitize
import ketch.core.config.Config
import ketch.core.config.validateRepo
import ketch.core.diff.unified
import ketch.core.error.KetchError
import ketch.core.error.ioAt
import ketch.core.install.InstallRequest
import ketch.core.install.install
import ketch.core.log.timestamp
import ketch.core.manifest.toToml
import ketch.core.manifest.userManifestPath
import ketch.core.model.Manifest
import ketch.core.model.PackageSpec
import ketch.core.model.nowUnix
import ketch.core.push.GitHub
import ketch.core.push.Outcome
import ketch.core.push.Plan
import ketch.core.push.Proposal
import ketch.core.push.current
import ketch.core.push.open
import ketch.core.registry.CheckReport
import ketch.core.registry.PACKAGE_FILE
import ketch.core.registry.UpdateMeta
import ketch.core.registry.ValidationError
import ketch.core.registry.agePhrase
import ketch.core.registry.checkTree
import ketch.core.registry.exists
import ketch.core.registry.fixturePayload
import ketch.core.registry.loadMeta
import ketch.core.source.SourceRegistry
import ketch.core.source.absolutePath
import ketch.core.source.packageRef
import ketch.core.state.State
import ketch.core.text.BYTE_ORDER
import ketch.core.text.splitWhitespace
import ketch.ui.bold
import ketch.ui.confirm
import ketch.ui.ctx
import ketch.ui.green
import ketch.ui.out
import ketch.ui.red
import ketch.ui.report
import ketch.ui.step
import ketch.ui.success
import ketch.ui.warn
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import ketch.core.push.load as loadProposal
import ketch.core.push.plan as planPush
import ketch.core.registry.load as loadRegistry

/** Entry point for `ketch registry <command>`. */
fun registry(
    cfg: Config,
    args: Matches,
) {
    val command = checkNotNull(args.subcommand)
    val sub = command.matches
    when (command.name) {
        "validate" -> validate(sub.value("dir")?.let(::Path), sub.value("fixture")?.let(::Path), sub.values("changed"), sub.flag("json"))
        "status" -> status(cfg, sub.flag("json"))
        else -> push(cfg, sub)
    }
}

/** `ketch registry validate`: every package folder, parsed and checked. */
private fun validate(
    given: Path?,
    fixture: Path?,
    changed: List<String>,
    json: Boolean,
) {
    val dir = given ?: Path(".")
    val tree = checkTree(dir)
    val errors = tree.errors.toMutableList()
    if (fixture == null && changed.isNotEmpty()) {
        errors += ValidationError(dir.toString(), "offline-install of --changed packages needs --fixture")
    } else if (fixture != null) {
        errors += probeFixtures(dir, fixture, changed, tree.parsed)
    }
    val report = tree.copy(errors = errors)
    if (json) {
        printValidateJson(report)
    } else {
        // One error per line, so a CI log can be grepped for them; the paths and messages come from a tree someone
        // else wrote, so they pass through the filter a changelog does. `--json` keeps the original text.
        for (error in errors) out(oneLine("${sanitize(error.path)}: ${sanitize(error.message)}"))
        if (errors.isEmpty()) success("validated", count(report.packages, "package"))
    }
    // The errors are the output a failing run exists to produce and are already on stdout; this sets the exit code,
    // with the same sentence in both formats.
    if (errors.isNotEmpty()) throw KetchError.Msg(count(errors.size, "validation error"))
}

/** Fold a message onto one line: a parse error can span several. */
private fun oneLine(text: String) = text.splitWhitespace().joinToString(" ")

/** `1 package` / `2 packages`, for the two counts this command reports. */
private fun count(
    n: Int,
    what: String,
) = "$n $what${if (n == 1) "" else "s"}"

private fun printValidateJson(report: CheckReport) {
    // JSON is a machine format, but it is printed to a terminal too: the encoder escapes control characters and not
    // bidi overrides, and a package file someone else wrote can carry either.
    val errors = report.errors.map { jsonOf("path" to JsonPrimitive(sanitize(it.path)), "message" to JsonPrimitive(sanitize(it.message))) }
    printJson(
        jsonOf(
            "status" to JsonPrimitive(if (report.errors.isEmpty()) "ok" else "fail"),
            "packages" to JsonPrimitive(report.packages),
            "errors" to JsonArray(errors),
        ),
    )
}

/** `ketch registry status`: age and source of the local copy. No network. */
private fun status(
    cfg: Config,
    json: Boolean,
) {
    val packages = if (exists(cfg)) loadRegistry(ctx(cfg)).size else null
    val meta =
        try {
            loadMeta(cfg)
        } catch (e: KetchError) {
            if (json) printStatusJson("fail", cfg, null, packages, e.message)
            throw e
        }
    if (json) {
        printStatusJson(
            if (packages == null) {
                "missing"
            } else if (meta == null) {
                "unrecorded"
            } else {
                "ok"
            },
            cfg,
            meta,
            packages,
            null,
        )
        return
    }
    out("${bold("registry")}  ${sanitize(cfg.registry)}")
    when {
        packages == null -> {
            warn("no local copy; run `ketch update` to fetch ${cfg.registry}")
        }

        meta == null -> {
            out("${bold("packages")}  $packages")
            warn("fetch time unknown; run `ketch update`")
        }

        else -> {
            meta.revision?.let { out("${bold("revision")}  ${sanitize(it)}") }
            meta.etag?.let { out("${bold("etag")}  ${sanitize(it)}") }
            out("${bold("fetched")}  ${timestamp(meta.fetchedAt)} (${agePhrase(meta.fetchedAt)})")
            out("${bold("packages")}  $packages")
        }
    }
}

private fun printStatusJson(
    status: String,
    cfg: Config,
    meta: UpdateMeta?,
    packages: Int?,
    error: String?,
) {
    val now = nowUnix()
    printJson(
        jsonOf(
            "status" to JsonPrimitive(status),
            "repo" to JsonPrimitive(sanitize(cfg.registry)),
            "revision" to JsonPrimitive(meta?.revision?.let(::sanitize)),
            "etag" to JsonPrimitive(meta?.etag?.let(::sanitize)),
            "fetched_at" to (meta?.let { JsonPrimitive(it.fetchedAt) } ?: JsonNull),
            "fetched" to JsonPrimitive(meta?.let { timestamp(it.fetchedAt) }),
            "age_seconds" to (meta?.let { JsonPrimitive((now - it.fetchedAt).coerceAtLeast(0)) } ?: JsonNull),
            "packages" to (packages?.let(::JsonPrimitive) ?: JsonNull),
            "error" to JsonPrimitive(error),
        ),
    )
}

/** Offline-install selected registry entries from local assets into a throwaway root. */
private fun probeFixtures(
    registryDir: Path,
    fixture: Path,
    changed: List<String>,
    parsed: List<Pair<Manifest, Path>>,
): List<ValidationError> {
    if (!isDir(fixture)) return listOf(ValidationError(fixture.toString(), "no such directory"))
    val names =
        (if (changed.isEmpty()) parsed.map { it.first.name }.filter { SystemFileSystem.exists(Path(fixture, it)) } else changed)
            .distinct()
            .sortedWith(BYTE_ORDER)
    return inThrowawayRoot(registryDir) { root -> probeIn(root, registryDir, fixture, names, parsed.associate { it.first.name to it.first }) }
}

/** Run [body] on a fresh temporary directory, removed afterwards; a directory that cannot be made is the error. */
private fun inThrowawayRoot(
    registryDir: Path,
    body: (Path) -> List<ValidationError>,
): List<ValidationError> {
    val probe =
        try {
            TempDir.create()
        } catch (e: IOException) {
            return listOf(ValidationError(registryDir.toString(), "cannot create a throwaway root for offline-install: ${e.message}"))
        }
    return probe.use { body(it.path) }
}

private fun probeIn(
    root: Path,
    registryDir: Path,
    fixture: Path,
    names: List<String>,
    byName: Map<String, Manifest>,
): List<ValidationError> {
    val cfg =
        try {
            Config.load(root, report()).also { it.ensureDirs() }
        } catch (e: KetchError) {
            return listOf(ValidationError(registryDir.toString(), e.message))
        }
    return names.mapNotNull { name ->
        val manifest = byName[name] ?: return@mapNotNull ValidationError(registryDir.toString(), "no package named `$name` to offline-install")
        val at = Path(fixture, name).toString()
        try {
            val payload =
                try {
                    fixturePayload(fixture, name)
                } catch (e: KetchError) {
                    return@mapNotNull ValidationError(at, e.message)
                }
            installFromFixture(cfg, manifest, payload)
            null
        } catch (e: KetchError) {
            ValidationError(at, "offline-install failed: ${e.message}")
        }
    }
}

/** Install [manifest] from a local asset, using a throwaway ketch root. */
private fun installFromFixture(
    cfg: Config,
    manifest: Manifest,
    payload: Path,
) {
    val rewritten = manifest.copy(source = packageRef(absolutePath(payload.toString())))
    val path = userManifestPath(cfg, rewritten.name)
    ioAt(path) { atomic(path, toToml(rewritten).encodeToByteArray()) }
    install(ctx(cfg), SourceRegistry.load(ctx(cfg)), State(), InstallRequest(spec = PackageSpec.parse(rewritten.name)))
}

/**
 * `ketch registry push`: this project's package file, as a registry pull request, looking before it leaps. The
 * registry's current copy is fetched first: a new package goes straight to the pull request, an unchanged one opens
 * nothing, and an update shows its diff and asks before anything is sent.
 */
private fun push(
    cfg: Config,
    args: Matches,
) {
    val file = args.value("file")?.let(::Path) ?: Path(PACKAGE_FILE)
    val proposal = loadProposal(file)
    val target = args.value("registry")?.let { validateRepo("registry", it) } ?: cfg.registry
    val destination = "$target:${proposal.name}/$PACKAGE_FILE"
    if (args.flag("dry_run")) {
        step("would push", destination)
        // The file ends with its own newline and `out` adds one; without the trim the dry run prints a blank line the
        // real file does not have.
        out(proposal.body.trimEnd('\n'))
        return
    }
    val api = GitHub.create(ctx(cfg))
    val existing = current(api, target, proposal.name)
    when (planPush(existing, proposal)) {
        Plan.Add -> {
            step("new package", "${proposal.name} @ $target")
            reportPush(target, proposal.name, open(api, target, proposal))
        }

        Plan.Unchanged -> {
            success("unchanged", "$target already has this ${proposal.name}")
        }

        Plan.Update -> {
            // `plan` reached Update through this very value, so a null cannot happen; declining is the least
            // surprising thing to do with the impossible case.
            val text = existing?.text ?: return
            if (reviewUpdate(target, file, text, proposal, args.flag("yes"))) {
                step("pushing", destination)
                reportPush(target, proposal.name, open(api, target, proposal))
            }
        }
    }
}

/** Show the diff an update would make and ask whether to send it. */
private fun reviewUpdate(
    target: String,
    file: Path,
    registryText: String,
    proposal: Proposal,
    yes: Boolean,
): Boolean {
    out("--- $target ${proposal.name}/$PACKAGE_FILE (registry)")
    out("+++ $file (local)")
    for (line in reviewDiff(registryText, proposal.body).lines()) {
        out(
            when {
                line.startsWith('-') -> red(line)
                line.startsWith('+') -> green(line)
                else -> line
            },
        )
    }
    return yes || confirm("update ${proposal.name} in $target with this change?", false)
}

/**
 * The diff shown before an update, safe to print. The registry's copy is a file someone else wrote: an escape
 * sequence in it could redraw the very review the user is about to approve.
 */
internal fun reviewDiff(
    registry: String,
    local: String,
): String = sanitize(unified(registry, local))

/** The outcome lines the add and update paths share, so the two cannot drift apart in what they report. */
private fun reportPush(
    target: String,
    name: String,
    outcome: Outcome,
) = when (outcome) {
    Outcome.Unchanged -> success("unchanged", "$target already has this $name")
    is Outcome.Opened -> success(if (outcome.pull.alreadyOpen) "already open" else "opened", outcome.pull.url)
}
