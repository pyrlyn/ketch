// Command-line surface: the port of src/cli.rs.
//
// The tree is declared once, here, so help, errors, completions and man pages all come from the same arguments and
// a command implementation in cmd/ reads exactly what the user typed, with no re-packing in between.
package ketch.cli

import cli.Command
import cli.CommandBuilder
import cli.ValueKind
import cli.command
import ketch.core.selfupdate.displayVersion

/** clap's `u32`: a 64-bit read, then this range. */
internal val U32 = ValueKind.Ranged(0, UInt.MAX_VALUE.toLong())

/** The `ketch` command line. */
fun ketchCli(): Command =
    command("ketch") {
        version = displayVersion()
        about = "Catch releases straight from GitHub."
        longAbout =
            "ketch installs command-line tools and apps from GitHub releases on macOS, Linux, and Windows.\n" +
            "No taps, no formulae, no build step — it downloads what the project already ships."
        propagateVersion = true
        subcommandRequired = true
        globals()
        packageCommands()
        toolCommands()
    }

private fun CommandBuilder.globals() {
    option("root") {
        global = true
        valueName = "DIR"
        value = ValueKind.NonEmpty
        doc("ketch root directory (default: ~/.ketch)")
    }
    flag("verbose") {
        short = 'v'
        global = true
        conflictsWith("quiet")
        doc("Show what ketch is doing, including every request")
    }
    flag("quiet") {
        short = 'q'
        global = true
        doc("Only print errors and requested data")
    }
    flag("no_color") {
        global = true
        doc("Never emit ANSI colour")
    }
    flag("no_emoji") {
        global = true
        doc("Never put an emoji icon in front of a status line")
    }
}

internal fun CommandBuilder.yes(text: String = "Answer yes to every prompt") =
    flag("yes") {
        short = 'y'
        doc(text)
    }

internal fun CommandBuilder.json(text: String) = flag("json") { doc(text) }

internal fun CommandBuilder.dryRun(text: String) = flag("dry_run") { doc(text) }

internal fun CommandBuilder.draft(text: String) = flag("draft") { doc(text) }

internal fun CommandBuilder.prerelease(text: String) =
    flag("prerelease") {
        long = "pre"
        doc(text)
    }

internal fun CommandBuilder.jobs(what: String) =
    option("jobs") {
        short = 'j'
        valueName = "N"
        value = ValueKind.Size
        doc("$what at once (default: 4, or `jobs` in config.toml)")
    }

internal fun CommandBuilder.file(text: String) =
    option("file") {
        short = 'f'
        valueName = "FILE"
        value = ValueKind.NonEmpty
        doc(text)
    }

internal fun CommandBuilder.force(
    text: String,
    withShort: Boolean = true,
) = flag("force") {
    if (withShort) short = 'f'
    doc(text)
}

/** `PKG`: an installed name, an alias, or `owner/repo`. */
internal fun CommandBuilder.pkg(text: String) =
    positional("package") {
        required = true
        valueName = "PKG"
        doc(text)
    }

/** One or more names, none optional. */
internal fun CommandBuilder.names() =
    positional("names") {
        required = true
        multiple = true
        valueName = "NAME"
    }
