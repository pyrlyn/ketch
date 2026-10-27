// The commands that set ketch up rather than touch packages, in cli.rs's order.
package ketch.cli

import cli.CommandBuilder
import cli.PossibleValue
import cli.ValueKind
import ketch.core.shell.Shell

/** clap_complete's `Shell`, in its own order. */
internal val COMPLETION_SHELLS = listOf("bash", "elvish", "fish", "powershell", "zsh")

private const val NOTHING_TOUCHED = "Report what would change without touching anything"

internal fun CommandBuilder.toolCommands() {
    config()
    registry()
    path()
    subcommand("plugin") {
        doc("Manage source plugins")
        subcommandRequired = true
        subcommand("list") {
            doc("Show discovered source plugins")
            json("Emit JSON instead of a table")
        }
        subcommand("dir") { doc("Print the directory plugins are loaded from") }
    }
    self()
    subcommand("completions") {
        doc("Print a shell completion script, or install it with `--install`")
        positional("shell") {
            required = true
            value = ValueKind.OneOf(COMPLETION_SHELLS.map { PossibleValue(it) })
        }
        flag("install") { doc("Write the script into this shell's user completion directory") }
    }
    subcommand("man") {
        doc("Write ketch's man pages, one per command, into a directory (for packaging)")
        hidden = true
        option("out") {
            required = true
            valueName = "DIR"
            value = ValueKind.NonEmpty
            doc("Directory to write the pages into; created if missing")
        }
    }
}

/** Where `ketch import` looks the name up. Each source only converts a package whose downloads are GitHub assets. */
internal fun CommandBuilder.importCommands() {
    subcommand("winget") {
        doc("A winget package, by its `Publisher.Package` identifier (case-sensitive)")
        positional("id") {
            required = true
            valueName = "ID"
            doc("The winget identifier, e.g. `BurntSushi.ripgrep.MSVC`")
        }
        importOpts()
    }
    subcommand("brew") {
        doc("A Homebrew cask or formula, by its token")
        positional("name") {
            required = true
            valueName = "NAME"
            doc("The cask or formula name, e.g. `codex`")
        }
        flag("cask") {
            conflictsWith("formula")
            doc("Only look for a cask")
        }
        flag("formula") { doc("Only look for a formula") }
        importOpts()
    }
    subcommand("linux") {
        doc("An Arch Linux or AUR package, by its name")
        positional("name") {
            required = true
            valueName = "NAME"
            doc("The package name, e.g. `lazygit`")
        }
        importOpts()
    }
}

private fun CommandBuilder.importOpts() {
    dryRun("Print the manifest that would be written, and change nothing")
    yes()
}

private fun CommandBuilder.config() =
    subcommand("config") {
        doc("Write a package config (`ketch.toml`) by answering questions")
        subcommandRequired = true
        subcommand("create") {
            doc("Create a `ketch.toml` by asking what each field should say")
            file("File to write (default: ./ketch.toml)")
            force("Replace the file even if one is already there", withShort = false)
            yes("Write the file without the final confirmation")
        }
        subcommand("reset") {
            doc("Reset `config.toml` in the ketch root to the compiled defaults")
            yes("Write without asking first")
        }
    }

private fun CommandBuilder.registry() =
    subcommand("registry") {
        doc("Compare a package config with the registry's copy before pulling-requesting it")
        subcommandRequired = true
        subcommand("validate") {
            doc("Validate a registry tree (every `ketch.toml`) the way the pre-push hook does")
            positional("dir") {
                valueName = "DIR"
                value = ValueKind.NonEmpty
                doc("Registry tree to validate (default: current directory)")
            }
            option("fixture") {
                valueName = "DIR"
                value = ValueKind.NonEmpty
                doc(
                    """
                    Local assets for offline-install of changed entries (one file, or
                    a folder of one file, per package name)
                    """.trimIndent(),
                )
            }
            option("changed") {
                multiple = true
                valueName = "NAME"
                doc(
                    """
                    Package names to offline-install against `--fixture` (repeatable).
                    Without this, every package that has a fixture is installed.
                    """.trimIndent(),
                )
            }
            json("Emit JSON instead of text")
        }
        subcommand("status") {
            doc("Show the local registry copy's age and source, without fetching")
            json("Emit JSON instead of text")
        }
        registryPush()
    }

private fun CommandBuilder.registryPush() =
    subcommand("push") {
        doc(
            """
            Compare this project's `ketch.toml` with the registry's copy, show the
            difference, and open a pull request with it
            """.trimIndent(),
        )
        file("Package file to push (default: ./ketch.toml)")
        option("registry") {
            valueName = "REPO"
            doc("Registry to open the pull request against, as `owner/repo`")
        }
        dryRun("Show what would be pushed, and push nothing")
        yes("Answer yes to the update prompt")
    }

private fun CommandBuilder.path() =
    subcommand("path") {
        doc("Put the ketch bin directory on PATH")
        subcommand("install") {
            doc("Add the bin directory to PATH")
            pathArgs()
            flag("print") {
                conflictsWith("all", "dry_run", "shell")
                doc("Print the line to add by hand instead of editing anything")
            }
        }
        subcommand("uninstall") {
            doc("Take the bin directory back off PATH")
            pathArgs()
        }
        subcommand("status") { doc("Show which shells have been set up") }
    }

private fun CommandBuilder.pathArgs() {
    option("shell") {
        multiple = true
        valueName = "SHELL"
        value = ValueKind.OneOf(Shell.entries.map { PossibleValue(it.shellName) })
        doc("Shells to act on. Default: the ones you appear to use.")
    }
    flag("all") {
        conflictsWith("shell")
        doc("Act on every shell ketch knows")
    }
    dryRun(NOTHING_TOUCHED)
}

private fun CommandBuilder.self() =
    subcommand("self") {
        doc("Manage ketch itself")
        subcommandRequired = true
        subcommand("install") {
            doc("Install this release of ketch as a package, into the store and bin dir")
            force("Reinstall even when this version is already the package")
            option("link_dir") {
                valueName = "DIR"
                value = ValueKind.NonEmpty
                doc("Place a bootstrap link or copy here, pointing at the bin-dir binary")
            }
        }
        subcommand("upgrade") {
            doc("Upgrade ketch to the latest release")
            alias("update")
            dryRun("Report what would happen without replacing anything")
            force("Reinstall even when already current")
            flag("pre") { doc("Take the newest release, prereleases included") }
            yes()
        }
        subcommand("version") { doc("Print the running version and where it lives") }
        selfUninstall()
    }

private fun CommandBuilder.selfUninstall() =
    subcommand("uninstall") {
        doc("Remove ketch and everything it installed, permanently")
        flag("keep_packages") { doc("Remove only ketch, leaving the packages it installed in place") }
        flag("no_brew") {
            doc(
                """
                Leave the Homebrew cask alone. Set by the cask's own uninstall,
                which is already removing it.
                """.trimIndent(),
            )
        }
        dryRun("Show what would be removed, and remove nothing")
        yes()
    }
