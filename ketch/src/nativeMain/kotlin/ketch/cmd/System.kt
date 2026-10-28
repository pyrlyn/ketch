// Commands about ketch itself and its environment: the port of src/cmd/system.rs.
package ketch.cmd

import cli.Matches
import cli.Subcommand
import ketch.cli.SELF_DOCS
import ketch.core.config.Config
import ketch.core.doctor.checks
import ketch.core.doctor.failed
import ketch.core.doctor.firstKetchOnPath
import ketch.core.doctor.sameBinary
import ketch.core.doctor.storeKetchLink
import ketch.core.error.KetchError
import ketch.core.model.CompletionShell
import ketch.core.platform.CheckStatus
import ketch.core.platform.DoctorCheck
import ketch.core.platform.worstStatus
import ketch.core.process.offerToStop
import ketch.core.selfupdate.SELF_NAME
import ketch.core.selfupdate.UninstallPlan
import ketch.core.selfupdate.currentExe
import ketch.core.selfupdate.currentVersion
import ketch.core.selfupdate.displayVersion
import ketch.core.selfupdate.installCompletionScript
import ketch.core.selfupdate.installSelf
import ketch.core.selfupdate.uninstallPlan
import ketch.core.selfupdate.uninstallSelf
import ketch.core.shell.Change
import ketch.core.shell.Outcome
import ketch.core.shell.Setup
import ketch.core.shell.Shell
import ketch.core.shell.ShellState
import ketch.core.shell.detected
import ketch.core.shell.manualLine
import ketch.core.source.PROTOCOL_VERSION
import ketch.core.source.discover
import ketch.core.state.State
import ketch.ui.Tone
import ketch.ui.bold
import ketch.ui.confirm
import ketch.ui.ctx
import ketch.ui.ctxAsking
import ketch.ui.dim
import ketch.ui.note
import ketch.ui.out
import ketch.ui.printable
import ketch.ui.step
import ketch.ui.success
import ketch.ui.table
import ketch.ui.tone
import ketch.ui.warn
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ketch.core.doctor.fix as doctorFix
import ketch.core.registry.update as updateRegistry
import ketch.core.shell.install as installShell
import ketch.core.shell.status as shellStatus
import ketch.core.shell.uninstall as uninstallShell

fun doctor(
    cfg: Config,
    args: Matches,
) {
    if (args.flag("fix")) {
        for (setup in doctorFix(ctx(cfg))) reportSetup(setup, false)
    }
    val checks = checks(ctx(cfg))
    if (args.flag("json")) {
        printJson(doctorReport(checks))
    } else {
        for (check in checks) {
            val (mark, name) =
                when (check.status) {
                    CheckStatus.Ok -> tone(Tone.Success, "ok  ") to dim(check.name)
                    CheckStatus.Warn -> tone(Tone.Warning, "warn") to bold(check.name)
                    CheckStatus.Fail -> tone(Tone.Error, "fail") to bold(check.name)
                }
            out("$mark $name  ${printable(check.detail)}")
            // The fix belongs with the problem, not in a summary the user has to map back onto the list.
            check.fix?.let { out("     ${dim(printable(it))}") }
        }
    }
    val failed = failed(checks)
    if (failed > 0) throw KetchError.Msg("$failed checks failed")
}

private fun statusName(status: CheckStatus) =
    when (status) {
        CheckStatus.Ok -> "ok"
        CheckStatus.Warn -> "warn"
        CheckStatus.Fail -> "fail"
    }

internal fun doctorReport(checks: List<DoctorCheck>): JsonObject =
    jsonOf(
        "status" to JsonPrimitive(statusName(worstStatus(checks))),
        "checks" to
            JsonArray(
                checks.map {
                    jsonOf(
                        "name" to JsonPrimitive(it.name),
                        "status" to JsonPrimitive(statusName(it.status)),
                        "detail" to JsonPrimitive(it.detail),
                        "fix" to (it.fix?.let(::JsonPrimitive) ?: JsonNull),
                    )
                },
            ),
    )

/** Write one shell's completion script into the platform destination and record it on the installed `ketch` package. */
fun installCompletions(
    cfg: Config,
    args: Matches,
) {
    val shell = CompletionShell.entries.first { it.asStr == args.value("shell") }
    installCompletionScript(ctx(cfg), shell, SELF_DOCS)
}

/** Refresh the local copy of the package registry. */
fun update(cfg: Config) {
    val count = updateRegistry(ctx(cfg))
    success("updated", "$count packages from ${cfg.registry}")
}

/** Refresh the registry when `auto_update` is on. A failed fetch is a warning: the copy already on disk still serves. */
internal fun maybeAutoUpdate(cfg: Config) {
    if (!cfg.autoUpdate) return
    step("auto-update", "enabled")
    try {
        update(cfg)
    } catch (e: KetchError) {
        warn(e.message)
    }
}

/**
 * `ketch path` — the shell setup the rest of ketch only ever hints at. This is the one command that writes outside
 * the ketch root, which is why it is a command at all rather than something `install` does behind the user's back.
 */
fun path(
    cfg: Config,
    command: Subcommand?,
) {
    when (command?.name) {
        "install" -> pathInstall(cfg, command.matches)
        "uninstall" -> for (sh in chosen(command.matches)) report(uninstallShell(sh, dryRun(command.matches)), dryRun(command.matches))
        else -> pathStatus(cfg)
    }
}

private fun pathStatus(cfg: Config) {
    val status = shellStatus(cfg)
    out("${bold("bin")}  ${cfg.binDir}")
    out("${bold("now")}  ${if (status.onPath) "on PATH" else status.detail}")
    val rows = mutableListOf<List<String>>()
    status.userPath?.let { rows += listOf("user PATH", if (it) "configured" else "not set up", "HKCU\\Environment\\Path") }
    for (row in status.shells) {
        val state =
            when (row.state) {
                ShellState.Configured -> "configured"
                ShellState.NotSetUp -> "not set up"
                ShellState.NotInUse -> "not in use"
            }
        rows += listOf(row.shell.shellName, state, row.file.toString())
    }
    table(listOf("shell", "state", "file"), rows)
}

private fun dryRun(args: Matches) = args.flag("dry_run")

private fun pathInstall(
    cfg: Config,
    args: Matches,
) {
    if (args.flag("print")) {
        out(manualLine(cfg))
        return
    }
    var changed = false
    for (sh in chosen(args)) {
        val change = installShell(cfg, sh, dryRun(args))
        changed = changed || change.outcome != Outcome.Unchanged
        report(change, dryRun(args))
    }
    if (changed && !dryRun(args)) out("Open a new shell to pick it up.")
}

/** Which shells this invocation acts on: what was asked for, or what the machine looks like. */
private fun chosen(args: Matches): List<Shell> {
    val named = args.values("shell").map { name -> Shell.entries.first { it.shellName == name } }
    return when {
        args.flag("all") -> Shell.ALL

        // Rust's `dedup`: a shell named twice in a row is acted on once.
        named.isNotEmpty() -> named.filterIndexed { i, sh -> i == 0 || named[i - 1] != sh }

        else -> detected()
    }
}

/**
 * What `self uninstall` is about to delete, one line per kind of thing. Printed before the question rather than after
 * it: an answer to "remove everything?" means nothing unless everything is on screen next to it.
 */
private fun planLines(plan: UninstallPlan): List<String> {
    val lines = mutableListOf<String>()
    if (plan.packages.isNotEmpty()) lines += "packages: ${plan.packages.joinToString(", ")}"
    if (plan.root != null) {
        lines += "${plan.root} and everything in it"
    } else if (plan.exe != null) {
        lines += plan.exe.toString()
    }
    plan.shellFiles.forEach { lines += "the PATH block in $it" }
    plan.registry.forEach { lines += it.describe() }
    plan.powershellProfiles.forEach { lines += "the completion block in $it" }
    if (plan.cask != null) lines += "the Homebrew cask"
    return lines
}

// The Windows user PATH is a setup step only on Windows, which this build does not target.
private fun reportSetup(
    setup: Setup,
    dryRun: Boolean,
) {
    if (setup is Setup.ShellFile) report(setup.change, dryRun)
}

private fun report(
    change: Change,
    dryRun: Boolean,
) {
    val detail = "${change.file} (${change.shell.shellName})"
    when (change.outcome) {
        Outcome.Added -> if (dryRun) step("would add", detail) else success("added", detail)
        Outcome.Updated -> if (dryRun) step("would update", detail) else success("updated", detail)
        Outcome.Removed -> if (dryRun) step("would remove", detail) else success("removed", detail)
        Outcome.Unchanged -> step("unchanged", detail)
    }
}

fun plugin(
    cfg: Config,
    command: Subcommand,
) {
    if (command.name == "dir") {
        out(cfg.pluginDir.toString())
        return
    }
    val rows = mutableListOf<List<String>>()
    val found = mutableListOf<JsonObject>()
    for (result in discover(ctx(cfg))) {
        result
            .onSuccess { p ->
                rows += listOf(p.scheme(), p.name, p.path.toString())
                found +=
                    jsonOf(
                        "scheme" to JsonPrimitive(p.scheme()),
                        "name" to JsonPrimitive(p.name),
                        "path" to JsonPrimitive(p.path.toString()),
                        "protocol" to JsonPrimitive(PROTOCOL_VERSION),
                    )
            }
            // A plugin ketch cannot speak to is still worth naming: the alternative is a scheme that silently does not exist.
            .onFailure { warn(it.message.orEmpty()) }
    }
    when {
        command.matches.flag("json") -> printJson(JsonArray(found))
        rows.isEmpty() -> out("no plugins in ${cfg.pluginDir}")
        else -> table(listOf("scheme", "plugin", "path"), rows)
    }
}

fun zelf(
    cfg: Config,
    command: Subcommand,
) {
    val args = command.matches
    when (command.name) {
        "install" -> selfInstall(cfg, args)
        "version" -> selfVersion(cfg)
        "upgrade" -> selfUpgrade(cfg, args)
        else -> selfUninstall(cfg, args)
    }
}

private fun selfInstall(
    cfg: Config,
    args: Matches,
) {
    val version = currentVersion()
    try {
        val installed = installSelf(ctx(cfg), args.flag("force"), args.value("link_dir")?.let(::Path), SELF_DOCS)
        val old = installed.replaced?.takeIf { it != installed.pkg.version }
        success("installed", if (old != null) "ketch $version (was $old)" else "ketch $version")
    } catch (_: KetchError.AlreadyInstalled) {
        // Installers run this on every invocation; the second run is a success, not a complaint.
        success("already installed", "ketch $version")
    }
}

private fun selfVersion(cfg: Config) {
    out("ketch ${displayVersion()}")
    out("target ${cfg.target}")
    out("root   ${cfg.root}")
    runCatching { currentExe() }.onSuccess { out("binary $it") }
    val onPath = firstKetchOnPath()
    if (onPath != null && !sameBinary(onPath, storeKetchLink(cfg))) out("PATH   $onPath")
}

private fun selfUpgrade(
    cfg: Config,
    args: Matches,
) {
    val (dryRun, force, yes) = Triple(dryRun(args), args.flag("force"), args.flag("yes"))
    if (!dryRun) offerToStop(selfReplacementPaths(cfg), yes, ctxAsking(cfg, !yes))
    val result = ketch.core.selfupdate.update(ctx(cfg), force, dryRun, args.flag("pre"), SELF_DOCS)
    // `replaced` is false both when already current and on dry-run, so the verb has to look at whether an upgrade is
    // actually needed.
    val needsUpdate = result.to > result.from || force
    val verb =
        when {
            result.replaced -> "upgraded"
            dryRun && needsUpdate -> "would upgrade"
            else -> "already current"
        }
    success(verb, "${result.from} -> ${result.to}")
    result.notes?.let { out(printable(it)) }
}

private const val REMOVE_KETCH = "remove ketch? this is permanent: nothing here can be recovered"
private const val REMOVE_EVERYTHING =
    "remove ketch and every package it installed? this is permanent: all of this data is deleted for good, with no way to undo it"

private fun selfUninstall(
    cfg: Config,
    args: Matches,
) {
    val (dryRun, yes, keep) = Triple(dryRun(args), args.flag("yes"), args.flag("keep_packages"))
    var plan = uninstallPlan(cfg, keep, args.flag("no_brew"))
    for (line in planLines(plan)) step(if (dryRun) "would remove" else "will remove", line)
    plan.mise?.let { step(if (dryRun) "would ask" else "will ask", "to run `mise unuse -g ${it.tool}`") }
    if (dryRun || (!yes && !confirm(if (keep) REMOVE_KETCH else REMOVE_EVERYTHING, false))) return
    // A question of its own: mise's copy belongs to another tool's config, and someone may want ketch's tree gone
    // but mise's entry kept — to reinstall from it, or because a project pins it.
    plan.mise?.let { mise ->
        val run = "mise unuse -g ${mise.tool}"
        if (!yes && !confirm("also run `$run`?", false)) {
            plan = plan.copy(mise = null)
            note("mise still has this ketch; `$run` removes it")
        }
    }
    for (path in uninstallSelf(ctx(cfg), plan)) success("removed", path.toString())
}

/** Binaries `self upgrade` may overwrite: the running image, the store link, and any recorded links for `ketch`. */
private fun selfReplacementPaths(cfg: Config): List<Path> {
    val paths = mutableListOf<Path>()
    runCatching { currentExe() }.onSuccess { paths += it }
    paths += storeKetchLink(cfg)
    runCatching { State.load(cfg) }.getOrNull()?.get(SELF_NAME)?.links?.forEach {
        paths += it.link
        paths += it.target
    }
    return paths
}
