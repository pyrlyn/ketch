// The commands that install, inspect and change packages, in cli.rs's order.
package ketch.cli

import cli.CommandBuilder
import cli.PossibleValue
import cli.ValueKind

private const val ONE_BIN = "Which binary to link when several share the package's name (single package)"
private const val DRAFT_PUSH = "Consider draft releases too (GitHub, with a token that has push access)"
private const val AT_ONCE = "Packages to work on"
private const val NAMED = "An installed name, an alias, or `owner/repo`"
private const val NAMED_AT = "An installed name, an alias, or `owner/repo` — may carry `@version`"

internal fun CommandBuilder.packageCommands() {
    install()
    subcommand("import") {
        doc("Add a winget, Homebrew or Linux package as a ketch manifest and install it")
        subcommandRequired = true
        importCommands()
    }
    uninstall()
    list()
    outdated()
    info()
    releases()
    why()
    changelog()
    search()
    history()
    subcommand("stats") {
        doc("Summarise everything ketch has recorded")
        json("Emit JSON instead of formatted text")
    }
    subcommand("update") { doc("Refresh the package registry (see `upgrade` for installed packages)") }
    upgrade()
    rollback()
    prune()
    subcommand("pin") {
        doc("Hold a package at its current version")
        names()
    }
    subcommand("unpin") {
        doc("Release a pin")
        names()
    }
    subcommand("link") {
        doc("Re-create the links for an installed package")
        names()
    }
    subcommand("unlink") {
        doc("Remove the links for an installed package, keeping it installed")
        names()
    }
    lockAndSync()
    subcommand("doctor") {
        doc("Check the environment and the install tree")
        flag("fix") { doc("Repair what can be repaired without asking: currently the PATH setup") }
        json("Emit JSON instead of text")
    }
}

private fun CommandBuilder.install() =
    subcommand("install") {
        doc("Install one or more packages")
        alias("i")
        positional("packages") {
            multiple = true
            valueName = "PKG"
            requiredUnlessPresent("path")
            doc("`owner/repo`, `scheme:id`, `local:<path>`, or a known alias — each may carry `@version`")
        }
        option("path") {
            valueName = "PATH"
            value = ValueKind.NonEmpty
            doc("Install from a local archive, binary, symlink, or `.app` (sets `local:<PATH>`)")
        }
        option("name") {
            valueName = "NAME"
            doc("Installed name for a single package (with `--path`, defaults to the file basename)")
        }
        option("bin") {
            valueName = "NAME"
            doc(ONE_BIN)
        }
        force("Reinstall even when the requested version is already present")
        prerelease("Consider prereleases when resolving `latest`")
        draft(DRAFT_PUSH)
        flag("no_link") { doc("Unpack and record the package without putting it on PATH") }
        flag("require_checksum") { doc("Refuse to install unless the release publishes a checksum") }
        flag("allow_unverified") { doc("Replace a checksum-verified install with a release that publishes no checksum") }
        option("asset") {
            valueName = "NAME"
            doc("Use this release asset by exact file name instead of auto-selecting")
        }
        jobs(AT_ONCE)
        yes()
    }

private fun CommandBuilder.uninstall() =
    subcommand("uninstall") {
        doc("Remove installed packages")
        alias("remove", "rm")
        names()
        yes()
    }

private fun CommandBuilder.list() =
    subcommand("list") {
        doc("Show installed and available packages")
        alias("ls")
        positional("mode") {
            valueName = "MODE"
            value =
                ValueKind.OneOf(
                    listOf(
                        PossibleValue("local", "Installed packages, from the state file"),
                        PossibleValue("remote", "Packages the registry offers"),
                    ),
                )
            doc(
                """
                `local`: installed packages only, no network. `remote`: the registry.
                Omit for both in one table, with the latest version of each.
                """.trimIndent(),
            )
        }
        json("Emit JSON instead of a table")
        flag("names_only") {
            conflictsWith("json")
            doc("Print only package names, one per line")
        }
        prerelease("Look for prereleases in the `latest` column")
        draft(
            """
            Look for drafts too in the `latest` column of installed GitHub
            packages (needs a token with push access)
            """.trimIndent(),
        )
        flag("installed") {
            hidden = true
            conflictsWith("mode")
            doc(
                """
                Same as `ketch list local`. Bare `ketch list` used to mean installed
                only; this hidden alias gives scripts one release to move to `local`.
                """.trimIndent(),
            )
        }
    }

private fun CommandBuilder.outdated() =
    subcommand("outdated") {
        doc("Show installed packages that have a newer release")
        json("Emit a JSON object (`status`, `outdated`, `failed`, `unreachable`) instead of a table")
        prerelease("Compare against prereleases too")
        draft("Compare against draft releases too (GitHub, with a token that has push access)")
        jobs("Packages to check")
    }

private fun CommandBuilder.info() =
    subcommand("info") {
        doc("Show details about a package, installed or not")
        alias("show")
        pkg(NAMED)
        json("Emit JSON instead of formatted text")
        flag("assets") { doc("List the release's assets and how each one scored") }
        flag("releases") { doc("List the package's releases under the details (see `ketch releases`)") }
        flag("draft") {
            requires("releases")
            doc("List draft releases too (GitHub, needs a token with push access)")
        }
    }

private fun CommandBuilder.releases() =
    subcommand("releases") {
        doc("List a package's releases, newest first, and mark the installed one")
        pkg(NAMED)
        json("Emit JSON instead of a table")
        prerelease("Include prereleases")
        draft(
            """
            Include draft releases, and prereleases with them (GitHub, needs a
            token with push access)
            """.trimIndent(),
        )
        option("limit") {
            short = 'n'
            default = "20"
            value = ValueKind.Ranged(1, U32.max)
            doc("How many releases to show at most")
        }
    }

private fun CommandBuilder.why() =
    subcommand("why") {
        doc("Explain how a package would be resolved, without installing it")
        pkg(NAMED_AT)
        json("Emit JSON instead of formatted text")
    }

private fun CommandBuilder.changelog() =
    subcommand("changelog") {
        doc("Show what changed: the package's own changelog, or its release notes")
        pkg(NAMED_AT)
        flag("latest") { doc("Show the newest release instead of the installed one") }
        flag("file") {
            conflictsWith("release", "latest")
            doc("Only read the changelog file the package ships")
        }
        flag("release") { doc("Only read the notes published with the release") }
    }

private fun CommandBuilder.search() =
    subcommand("search") {
        doc("Search GitHub for installable repositories")
        positional("query") {
            required = true
            multiple = true
            valueName = "QUERY"
        }
        option("limit") {
            short = 'n'
            default = "15"
            value = ValueKind.Size
            doc("Maximum results")
        }
    }

private fun CommandBuilder.history() =
    subcommand("history") {
        doc("Show what was installed, upgraded and removed, newest first")
        positional("package") {
            valueName = "PKG"
            doc("Limit to one package. Omit for everything, in one timeline.")
        }
        json("Emit JSON instead of a table")
        option("limit") {
            short = 'n'
            default = "20"
            value = U32
            doc("How many entries to show")
        }
    }

private fun CommandBuilder.upgrade() =
    subcommand("upgrade") {
        doc("Upgrade installed packages to their latest release")
        positional("names") {
            multiple = true
            valueName = "NAME"
            doc("Packages to upgrade. Empty means every unpinned package.")
        }
        dryRun("Report what would change without touching anything")
        prerelease("Consider prereleases")
        draft(DRAFT_PUSH)
        force("Upgrade pinned packages too", withShort = false)
        flag("allow_unverified") { doc("Upgrade a checksum-verified package to a release that publishes no checksum") }
        option("bin") {
            valueName = "NAME"
            doc(ONE_BIN)
        }
        jobs(AT_ONCE)
        yes()
    }

private fun CommandBuilder.rollback() =
    subcommand("rollback") {
        doc("Switch a package back to a version still on disk")
        pkg("An installed name, a binary it provides, or `owner/repo`")
        option("to") {
            valueName = "VERSION"
            doc("Version to restore. Default: the previous retained version.")
        }
    }

private fun CommandBuilder.prune() =
    subcommand("prune") {
        doc("Remove retained prefixes according to the retention policy")
        positional("names") {
            multiple = true
            valueName = "NAME"
            doc("Packages to prune. Empty means every installed package.")
        }
        option("keep") {
            valueName = "N"
            value = U32
            doc("Previous versions to keep per package (updates the stored policy)")
        }
    }

private fun CommandBuilder.lockAndSync() {
    subcommand("lock") {
        doc("Write or check `ketch.lock`, a reproducible record of what is installed")
        file("Lockfile to write (default: ./ketch.lock)")
        flag("check") { doc("Report how the tree differs from the lockfile, and write nothing") }
    }
    subcommand("sync") {
        doc("Install everything `ketch.lock` names, at the versions it names")
        file("Lockfile to read (default: ./ketch.lock)")
        flag("prune") { doc("Also remove installed packages the lockfile does not name") }
        dryRun("Report what would change without installing anything")
        jobs(AT_ONCE)
        yes()
    }
}
