// Shell completion: the port of src/complete.rs.
//
// The kit's generators write the static part — commands, aliases, flags — from `ketchCli()`. Package names are not
// known when a script is generated, so the bash and PowerShell scripts call back into `ketch __complete <kind>`,
// which prints the names one per line, with the same table of commands for both.
//
// `__complete` is ours rather than clap_complete's dynamic protocol because ketch writes its script to disk at
// `self install`: a protocol that changed with a library release would leave every installed script talking to a
// binary that no longer answers. It is read before the parser sees the command line, rather than declared as a
// hidden subcommand, because the bash generator lists hidden subcommands too and `ketch <TAB>` must not offer it.
package ketch.cli

import cli.Command
import cli.CompletionShell
import cli.completions
import cli.valueOptions
import ketch.core.config.Config
import ketch.core.config.sanitizeComponent
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.state.State

/** The argument that routes a run to the completion lookup instead of the command line. */
internal const val COMPLETE_COMMAND = "__complete"

/** Which names a completion asks for. */
internal enum class Kind(
    val id: String,
) {
    /** Packages recorded in the state file. */
    Installed("installed"),

    /** Packages in the local registry copy. Never fetched: completion runs on every <TAB> and must not wait. */
    Registry("registry"),
}

/**
 * Subcommands whose positional argument is a package name, and which names fit it. `why`, `info` and `releases` also
 * take names that are not installed, but the installed ones are what a user reaches for most.
 */
internal val DYNAMIC =
    listOf(
        "uninstall" to Kind.Installed,
        "upgrade" to Kind.Installed,
        "pin" to Kind.Installed,
        "unpin" to Kind.Installed,
        "link" to Kind.Installed,
        "unlink" to Kind.Installed,
        "info" to Kind.Installed,
        "releases" to Kind.Installed,
        "why" to Kind.Installed,
        "changelog" to Kind.Installed,
        "rollback" to Kind.Installed,
        "install" to Kind.Registry,
        "search" to Kind.Registry,
    )

/**
 * Names of [kind] starting with [prefix], sorted and without duplicates.
 *
 * Registry folder names are written by someone else, and the bash script puts each one straight into the command
 * line being edited, so only plain names pass: anything a shell would treat as syntax is dropped, not quoted.
 */
internal fun candidates(
    cfg: Config,
    kind: Kind,
    prefix: String,
): List<String> {
    val names =
        when (kind) {
            Kind.Installed -> {
                runCatching { State.load(cfg).names() }.getOrDefault(emptyList())
            }

            Kind.Registry -> {
                ketch.core.registry
                    .load(Ctx(cfg, Report.silent()))
                    .map { it.first.name }
            }
        }
    return names.filter { it.startsWith(prefix) && isPlain(it) }.distinct().sorted()
}

internal fun isPlain(name: String): Boolean = sanitizeComponent(name) == name && name.all { (it.isLetterOrDigit() && it.code < ASCII) || it in "-_.+@" }

private const val ASCII = 0x80

/** The completion script for [shell], as `ketch completions` prints it. */
fun script(shell: CompletionShell): String {
    val cli = ketchCli()
    val base = completions(cli, shell, cli.name)
    return when (shell) {
        CompletionShell.Bash -> base + bashPackages(cli)
        CompletionShell.PowerShell -> powershellWithPackages(base, cli)
        else -> base
    }
}

/** A dynamic subcommand's names, whether it takes several packages, and its value-taking options. */
private class Dynamic(
    val names: List<String>,
    val kind: Kind,
    val multi: Boolean,
    val takes: List<String>,
)

private fun dynamic(cli: Command): List<Dynamic> =
    DYNAMIC.mapNotNull { (name, kind) ->
        cli.subcommands.firstOrNull { it.name == name }?.let { sub ->
            Dynamic(listOf(sub.name) + sub.aliases, kind, sub.args.any { it.positional && it.multiple }, valueOptions(cli, name))
        }
    }

/**
 * The line clap's PowerShell completer opens with. The package lookup goes straight after it, inside clap's own
 * script block, so there is one completer to register and no closure to keep alive.
 */
internal const val POWERSHELL_PARAM = "param(\$wordToComplete, \$commandAst, \$cursorPosition)\n"

/**
 * Package-name completion spliced into the PowerShell completer, the same rules as [bashPackages]. Written for
 * Windows PowerShell 5.1 as well as 7: no `??`, no ternary. Comparisons are the case-sensitive `-ceq`/`-ccontains`,
 * because subcommands and flags are. A script without [POWERSHELL_PARAM] is left as the generator wrote it.
 */
internal fun powershellWithPackages(
    script: String,
    cli: Command,
): String {
    val at = script.indexOf(POWERSHELL_PARAM)
    if (at < 0) return script
    val cases =
        dynamic(cli).withIndex().joinToString("") { (i, sub) ->
            val keyword = if (i == 0) "" else "else"
            "                ${keyword}if (@(${powershellList(sub.names)}) -ccontains \$sub) { \$kind = '${sub.kind.id}'; " +
                "\$multi = \$${sub.multi}; \$takes += @(${powershellList(sub.takes)}) }\n"
        } + "                else { break }\n"
    val block =
        POWERSHELL_PACKAGES
            .replace("@GLOBAL@", powershellList(valueOptions(cli)))
            .replace("@CASES@", cases)
            .replace("@COMMAND@", COMPLETE_COMMAND)
    val split = at + POWERSHELL_PARAM.length
    return script.substring(0, split) + block + script.substring(split)
}

/** `'a','b'`: every item is a command name or flag, so none holds a quote. */
private fun powershellList(items: List<String>): String = items.joinToString(",") { "'${it.replace("'", "''")}'" }

private val POWERSHELL_PACKAGES =
    """
    |
    |    # Package names for the commands that take them, from `ketch @COMMAND@`.
    |    # Any other position falls through to clap's completions below.
    |    ${'$'}packages = & {
    |        ${'$'}takes = @(@GLOBAL@)
    |        ${'$'}words = @(${'$'}commandAst.CommandElements | Where-Object { ${'$'}_.Extent.EndOffset -lt ${'$'}cursorPosition })
    |        ${'$'}texts = @(${'$'}words | ForEach-Object { if (${'$'}_ -is [StringConstantExpressionAst]) { ${'$'}_.Value } else { ${'$'}_.Extent.Text } })
    |        ${'$'}sub = ${'$'}null; ${'$'}kind = ${'$'}null; ${'$'}multi = ${'$'}false; ${'$'}root = ${'$'}null; ${'$'}seen = 0
    |        for (${'$'}i = 1; ${'$'}i -lt ${'$'}texts.Count; ${'$'}i++) {
    |            ${'$'}word = [string]${'$'}texts[${'$'}i]
    |            if (${'$'}word -ceq '--root') {
    |                if (${'$'}i + 1 -lt ${'$'}texts.Count) { ${'$'}root = [string]${'$'}texts[${'$'}i + 1] }
    |                ${'$'}i++
    |                continue
    |            }
    |            if (${'$'}word.StartsWith('--root=')) { ${'$'}root = ${'$'}word.Substring(7); continue }
    |            if (${'$'}null -eq ${'$'}sub) {
    |                if (${'$'}takes -ccontains ${'$'}word) { ${'$'}i++; continue }
    |                if (${'$'}word.StartsWith('-')) { continue }
    |                ${'$'}sub = ${'$'}word
    |@CASES@                continue
    |            }
    |            if (${'$'}takes -ccontains ${'$'}word) { ${'$'}i++ }
    |            elseif (-not ${'$'}word.StartsWith('-')) { ${'$'}seen++ }
    |        }
    |        if (${'$'}null -eq ${'$'}kind) { return }
    |        if (${'$'}wordToComplete.StartsWith('-') -or (${'$'}takes -ccontains [string]${'$'}texts[${'$'}texts.Count - 1])) { return }
    |        if (-not ${'$'}multi -and ${'$'}seen -gt 0) { return }
    |        ${'$'}request = @('@COMMAND@')
    |        if (${'$'}root) { ${'$'}request += @('--root', ${'$'}root) }
    |        ${'$'}request += @(${'$'}kind, '--', ${'$'}wordToComplete)
    |        & ${'$'}texts[0] @request 2>${'$'}null | Where-Object { ${'$'}_ } | ForEach-Object {
    |            [CompletionResult]::new(${'$'}_, ${'$'}_, [CompletionResultType]::ParameterValue, ${'$'}_)
    |        }
    |    }
    |    if (${'$'}packages) { return ${'$'}packages }
    |
    """.trimMargin()

/**
 * A bash function that completes package names for the subcommands in [DYNAMIC] and hands every other case to the
 * generated `_<name>`, then registers itself in its place. The value-taking options come from the command tree, so
 * an option added later is skipped over without touching this function. Bash 3.2 compatible: macOS still ships it.
 */
private fun bashPackages(cli: Command): String {
    val name = cli.name
    val cases = bashCases(cli)
    val function = "_${name}_packages"
    val d = '$'
    return """
        |
        |# Package names for the commands that take them, from `$name $COMPLETE_COMMAND`.
        |# Everything else goes to clap's _$name above.
        |$function() {
        |    local cur="$d{COMP_WORDS[COMP_CWORD]}" prev="$d{COMP_WORDS[COMP_CWORD-1]}"
        |    local bin="$d{COMP_WORDS[0]}" i word sub="" kind="" multi="" root="" line
        |    local takes=" ${valueOptions(cli).joinToString(" ")} "
        |    local seen=0
        |    for (( i = 1; i < COMP_CWORD; i++ )); do
        |        word="$d{COMP_WORDS[i]}"
        |        case "${d}word" in
        |            --root) root="$d{COMP_WORDS[i+1]}"; (( i++ )); continue ;;
        |            --root=*) root="$d{word#--root=}"; continue ;;
        |        esac
        |        if [[ -z "${d}sub" ]]; then
        |            [[ "${d}word" == -* ]] && continue
        |            sub="${d}word"
        |            case "${d}sub" in
        |$cases                *) break ;;
        |            esac
        |            continue
        |        fi
        |        if [[ "${d}takes" == *" ${d}word "* ]]; then
        |            (( i++ ))
        |        elif [[ "${d}word" != -* ]]; then
        |            (( seen++ ))
        |        fi
        |    done
        |    if [[ -z "${d}kind" || "${d}cur" == -* || "${d}takes" == *" ${d}prev "* ]] || [[ -z "${d}multi" && ${d}seen -gt 0 ]]; then
        |        _$name "$d@"
        |        return
        |    fi
        |    [[ "${d}bin" == "~/"* ]] && bin="${d}HOME/$d{bin#\~/}"
        |    [[ "${d}root" == "~/"* ]] && root="${d}HOME/$d{root#\~/}"
        |    local args=($COMPLETE_COMMAND)
        |    [[ -n "${d}root" ]] && args+=(--root "${d}root")
        |    args+=("${d}kind" -- "${d}cur")
        |    COMPREPLY=()
        |    while IFS= read -r line; do
        |        [[ -n "${d}line" ]] && COMPREPLY+=("${d}line")
        |    done < <("${d}bin" "$d{args[@]}" 2>/dev/null)
        |}
        |
        |if [[ "$d{BASH_VERSINFO[0]}" -eq 4 && "$d{BASH_VERSINFO[1]}" -ge 4 || "$d{BASH_VERSINFO[0]}" -gt 4 ]]; then
        |    complete -F $function -o nosort -o bashdefault -o default $name
        |else
        |    complete -F $function -o bashdefault -o default $name
        |fi
        |
        """.trimMargin()
}

private fun bashCases(cli: Command): String =
    dynamic(cli).joinToString("") { sub ->
        "                ${sub.names.joinToString("|")}) kind=${sub.kind.id}; multi=${if (sub.multi) "1" else ""}; " +
            "takes=\" ${sub.takes.joinToString(" ")} \" ;;\n"
    }
