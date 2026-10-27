// ketch — catch releases straight from GitHub: the port of src/main.rs.
//
// `main` parses arguments and hands off to a command; every failure path converges here so a single place decides
// how errors are shown and what the process exits with.
package ketch.cli

import cli.CompletionShell
import cli.Matches
import cli.Parsed
import cli.parse
import cli.terminalSettings
import cli.write
import platform.posix.fputs
import platform.posix.stderr
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (val parsed = parse(ketchCli(), args.toList(), terminalSettings("ketch"))) {
        is Parsed.Exit -> {
            parsed.write()
            exitProcess(parsed.code)
        }

        is Parsed.Run -> {
            exitProcess(run(parsed.matches))
        }
    }
}

// The commands land with the port of cmd/; until then a parsed line says which one is missing.
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun run(matches: Matches): Int {
    val command = matches.subcommand
    // Completions must work before any directory exists, so they are printed before the config is built.
    if (command?.name == "completions" && !command.matches.flag("install")) {
        val shell = CompletionShell.entries.first { it.id == command.matches.value("shell") }
        println(script(shell).trimEnd('\n'))
        return 0
    }
    fputs("error: `ketch ${matches.subcommand?.name}` is not ported yet\n", stderr)
    return 1
}
