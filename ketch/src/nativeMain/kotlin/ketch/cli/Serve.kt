// `ketch serve [--root DIR]`: the JSON-lines mode the desktop app runs (ketch.serve.Server).
//
// Read before the parser sees the command line, as `__complete` is, rather than declared as a hidden subcommand:
// `ketch --help`, the man pages and the completion scripts stay Rust ketch's, and the bash generator would offer a
// hidden subcommand on `ketch <TAB>`.
@file:OptIn(ExperimentalForeignApi::class)

package ketch.cli

import cli.Parsed
import cli.Settings
import cli.ValueKind
import cli.command
import cli.parse
import ketch.core.error.KetchError
import ketch.serve.KetchCore
import ketch.serve.Server
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.fflush
import platform.posix.stdout

/** The argument that routes a run to the desktop protocol instead of the command line. */
internal const val SERVE_COMMAND = "serve"

private val SERVE =
    command(SERVE_COMMAND) {
        disableHelpFlag = true
        option("root") {
            valueName = "DIR"
            value = ValueKind.NonEmpty
        }
    }

/** clap's exit code for a command line it cannot read. */
private const val USAGE_EXIT = 2

/** Serve the desktop app when [args] (without the binary's name) asks for it; null for every other command line. */
internal fun serveIntercept(args: List<String>): Int? = if (args.firstOrNull() == SERVE_COMMAND) serve(args.drop(1)) else null

private fun serve(args: List<String>): Int =
    when (val parsed = parse(SERVE, args, Settings(SERVE_COMMAND))) {
        is Parsed.Exit -> {
            // The parser's message carries its own `error: ` headline; ui adds one.
            ketch.ui.error(KetchError.Msg(parsed.text.removePrefix("error: ").trimEnd()))
            USAGE_EXIT
        }

        is Parsed.Run -> {
            val core = KetchCore(parsed.matches.value("root")?.let(::Path))
            val server =
                Server(core, ::readlnOrNull) {
                    println(it)
                    // The app reads a line as soon as it is written; stdout to a pipe is otherwise flushed when full.
                    fflush(stdout)
                }
            runBlocking { server.serve() }
            0
        }
    }
