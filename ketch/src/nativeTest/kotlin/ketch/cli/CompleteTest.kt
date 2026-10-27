package ketch.cli

import cli.Command
import cli.CompletionShell
import fs.TempDir
import fs.atomic
import fs.createDirAll
import ketch.core.config.Config
import ketch.core.registry.PACKAGE_FILE
import ketch.core.report.Report
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompleteTest {
    private fun words(script: String): Set<String> = script.split(Regex("[^A-Za-z0-9_-]")).filter { it.isNotEmpty() }.toSet()

    private fun walk(
        command: Command,
        script: Set<String>,
        missing: MutableList<String>,
    ) {
        for (arg in command.args) {
            if (arg.hidden || arg.positional) continue
            val flags = listOfNotNull(arg.long?.let { "--$it" }, arg.short?.let { "-$it" })
            flags.filter { it !in script }.forEach { missing += "${command.name} $it" }
        }
        for (sub in command.subcommands) {
            if (sub.hidden) continue
            (listOf(sub.name) + sub.aliases).filter { it !in script }.forEach { missing += "${command.name} $it" }
            walk(sub, script, missing)
        }
    }

    private fun missingFrom(shell: CompletionShell): List<String> = mutableListOf<String>().also { walk(ketchCli(), words(script(shell)), it) }

    @Test
    fun theBashScriptNamesEveryVisibleSubcommandAliasAndFlag() {
        assertEquals(emptyList(), missingFrom(CompletionShell.Bash))
    }

    @Test
    fun everyDynamicSubcommandExistsAndTakesOnePositional() {
        val cli = ketchCli()
        for ((name, _) in DYNAMIC) {
            val sub = cli.subcommands.first { it.name == name }
            assertEquals(1, sub.args.count { it.positional }, name)
        }
    }

    @Test
    fun theBashScriptRegistersThePackageCompleterLast() {
        val script = script(CompletionShell.Bash)
        val last = script.lines().last { it.trimStart().startsWith("complete -F") }
        assertTrue(last.contains("-F _ketch_packages "), last)
        assertTrue(script.contains("uninstall|remove|rm) kind=installed; multi=1;"))
        assertTrue(script.contains("rollback) kind=installed; multi=;"))
        assertTrue(script.contains("install|i) kind=registry; multi=1; takes=\" --path "))
    }

    @Test
    fun thePowershellScriptAsksForPackagesInsideClapsCompleter() {
        val script = script(CompletionShell.PowerShell)
        val param = script.indexOf(POWERSHELL_PARAM)
        val lookup = script.indexOf("\$packages = & {")
        val body = script.indexOf("\$commandElements = ")
        assertTrue(param in 0..<lookup && lookup < body, script)
        assertEquals(1, Regex("Register-ArgumentCompleter").findAll(script).count())
        assertTrue(
            script.contains(
                "if (@('uninstall','remove','rm') -ccontains \$sub) { \$kind = 'installed'; \$multi = \$true; ",
            ),
        )
        assertTrue(script.contains("elseif (@('rollback') -ccontains \$sub) { \$kind = 'installed'; \$multi = \$false; "))
        assertTrue(script.contains("elseif (@('install','i') -ccontains \$sub) { \$kind = 'registry'; "))
        assertTrue(script.contains("\$request = @('__complete')"))
        for (placeholder in listOf("@GLOBAL@", "@CASES@", "@COMMAND@")) {
            assertFalse(script.contains(placeholder), "$placeholder left unfilled")
        }
    }

    @Test
    fun thePowershellScriptNamesEveryVisibleSubcommandAliasAndFlag() {
        assertEquals(emptyList(), missingFrom(CompletionShell.PowerShell))
    }

    @Test
    fun aScriptWithoutTheParamLineIsLeftAlone() {
        assertEquals("# other\n", powershellWithPackages("# other\n", ketchCli()))
    }

    @Test
    fun otherShellsGetTheGeneratedScriptUnchanged() {
        assertFalse(script(CompletionShell.Zsh).contains(COMPLETE_COMMAND))
    }

    @Test
    fun plainNamesPassAndShellSyntaxDoesNot() {
        for (good in listOf("ripgrep", "fd-find", "gh_cli", "node@22", "c++", "a.b")) assertTrue(isPlain(good), good)
        for (bad in listOf("$(rm)", "a b", "a;b", "`x`", "-flag", "a/b", "é", "")) assertFalse(isPlain(bad), bad)
    }

    @Test
    fun candidatesAreFilteredByPrefixSortedAndPlain() {
        TempDir.create("ketch-test-").use { tmp ->
            val cfg = Config.load(tmp.path, Report.silent())
            for (name in listOf("ripgrep", "ripcord", "fd", "$(evil)")) {
                val dir = Path(cfg.registryDir, name)
                createDirAll(dir)
                atomic(Path(dir, PACKAGE_FILE), "name = \"$name\"\nsource = \"test:$name\"\n".encodeToByteArray())
            }
            assertEquals(listOf("ripcord", "ripgrep"), candidates(cfg, Kind.Registry, "rip"))
            assertEquals(3, candidates(cfg, Kind.Registry, "").size)
            assertEquals(emptyList(), candidates(cfg, Kind.Installed, ""))
        }
    }
}
