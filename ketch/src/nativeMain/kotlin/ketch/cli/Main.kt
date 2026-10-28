// ketch — catch releases straight from GitHub: the port of src/main.rs.
//
// `main` does three things and nothing else: parse arguments, build the `Config`, and hand off to a command. Every
// failure path converges here so a single place decides how errors are shown and what the process exits with.
package ketch.cli

import cli.CompletionShell
import cli.Matches
import cli.Parsed
import cli.Subcommand
import cli.parse
import cli.terminalSettings
import cli.write
import ketch.cmd.changelog
import ketch.cmd.doctor
import ketch.cmd.history
import ketch.cmd.info
import ketch.cmd.installCompletions
import ketch.cmd.list
import ketch.cmd.outdated
import ketch.cmd.releases
import ketch.cmd.search
import ketch.cmd.stats
import ketch.cmd.why
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.log.Log
import ketch.ui.debug
import ketch.ui.note
import ketch.ui.out
import ketch.ui.report
import ketch.ui.setEmoji
import ketch.ui.success
import ketch.ui.warnUnlogged
import kotlinx.io.files.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val argv = args.toList()
    intercept(argv)?.let { exitProcess(it) }
    when (val parsed = parse(ketchCli(), argv, terminalSettings("ketch"))) {
        is Parsed.Exit -> {
            parsed.write()
            exitProcess(parsed.code)
        }

        is Parsed.Run -> {
            exitProcess(start(parsed.matches, argv))
        }
    }
}

private fun start(
    cli: Matches,
    argv: List<String>,
): Int {
    ketch.ui.init(if (cli.flag("no_color")) false else null, cli.flag("quiet"), cli.flag("verbose"))
    return try {
        run(cli, argv)
        0
    } catch (err: KetchError) {
        if (err is KetchError.Reported) return err.code
        ketch.ui.error(err)
        // What npm and cargo do, and for the same reason: the terminal shows the failure, the log shows the run that
        // led to it.
        Log.path()?.let { note("the full log of this run is in $it") }
        err.exitCode()
    }
}

private fun run(
    cli: Matches,
    argv: List<String>,
) {
    val command = checkNotNull(cli.subcommand) { "the command tree requires a subcommand" }
    when {
        // Completions must work before any directory exists, so they are handled before the config is built.
        command.name == "completions" && !command.matches.flag("install") -> {
            val shell = CompletionShell.entries.first { it.id == command.matches.value("shell") }
            out(script(shell).trimEnd('\n'))
        }

        // Man pages are rendered from the command tree alone; a packager runs this on a build machine where no ketch
        // root should appear.
        command.name == "man" -> {
            man(Path(command.matches.values("out").single()))
        }

        // `config create` writes a project file in the working tree. Creating the ketch root for it would leave empty
        // store/bin/cache dirs behind a questionnaire that never uses them.
        command.name == "config" && command.matches.subcommand?.name == "create" -> {
            dispatch(load(cli), command)
        }

        else -> {
            val cfg = load(cli)
            cfg.ensureDirs()
            // Not `warn`: that would try to log the failure to the log.
            try {
                Log.init(cfg, cli.flag("verbose"), argv)
            } catch (e: KetchError) {
                warnUnlogged(e.message)
            }
            debug("root ${cfg.root} · target ${cfg.target} · token ${if (cfg.githubToken != null) "yes" else "no"}")
            dispatch(cfg, command)
        }
    }
}

private fun load(cli: Matches): Config {
    val cfg = Config.load(cli.value("root")?.let(::Path), report())
    setEmoji(cfg.emoji && !cli.flag("no_emoji"))
    return cfg
}

private fun man(out: Path) {
    val written = writeTo(out)
    success("Wrote", "${written.size} man pages to $out")
}

/** The commands that need nothing but the config and their own arguments. */
private val COMMANDS: Map<String, (Config, Matches) -> Unit> =
    mapOf(
        "list" to ::list,
        "outdated" to ::outdated,
        "info" to ::info,
        "releases" to ::releases,
        "why" to ::why,
        "changelog" to ::changelog,
        "search" to ::search,
        "history" to ::history,
        "stats" to ::stats,
        "doctor" to ::doctor,
        "completions" to ::installCompletions,
    )

private fun dispatch(
    cfg: Config,
    command: Subcommand,
) {
    val args = command.matches
    COMMANDS[command.name]?.let { return it(cfg, args) }
    when (command.name) {
        "update" -> ketch.cmd.update(cfg)

        "path" -> ketch.cmd.path(cfg, args.subcommand)

        "plugin" -> ketch.cmd.plugin(cfg, checkNotNull(args.subcommand))

        "self" -> ketch.cmd.zelf(cfg, checkNotNull(args.subcommand))

        "man" -> man(Path(args.values("out").single()))

        // The commands still being ported say which one is missing.
        else -> throw KetchError.Msg("`ketch ${command.name}` is not ported yet")
    }
}
