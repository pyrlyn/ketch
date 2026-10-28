// Bash completion, driven through a real bash (Rust tests/completion.rs): the script `ketch completions bash` prints is
// evaluated, and the function it registers for `ketch` is called the way bash calls it on <TAB>. On macOS that bash is
// /bin/bash 3.2, so this also proves the script needs nothing newer.
package ketch.e2e

import ketch.e2e.support.Sandbox
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.ok
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionTest {
    private val sandbox = Sandbox()
    private val others = mutableListOf<Sandbox>()

    @AfterTest
    fun cleanUp() {
        sandbox.close()
        others.forEach { it.close() }
    }

    /** Complete [line] (words split on spaces; a trailing space means an empty current word) as bash would. */
    private fun complete(line: String): List<String> {
        val words = line.split(' ').toMutableList()
        if (words.last().isEmpty()) words[words.lastIndex] = "''"
        val script =
            """
            |eval "${'$'}(ketch completions bash)"
            |fn=${'$'}(complete -p ketch | sed -n 's/.*-F \([^ ]*\).*/\1/p')
            |COMP_WORDS=(${words.joinToString(" ")})
            |COMP_CWORD=${'$'}(( ${'$'}{#COMP_WORDS[@]} - 1 ))
            |COMP_LINE="$line"
            |COMP_POINT=${'$'}{#COMP_LINE}
            |"${'$'}fn" ketch "${'$'}{COMP_WORDS[COMP_CWORD]}" "${'$'}{COMP_WORDS[COMP_CWORD-1]}"
            |printf '%s\n' "${'$'}{COMPREPLY[@]}"
            |
            """.trimMargin()
        val out = sandbox.ketchFrom(Path("bash"), listOf("-c", script), sandbox.ketchOnPathDir())
        assertTrue(out.success, "bash failed:\n${out.stderr}")
        return out.stdout
            .lines()
            .filter { it.isNotEmpty() }
            .sorted()
    }

    private fun installLocal(
        into: Sandbox,
        name: String,
    ) {
        val fixture = into.fixture(name)
        writeText(fixture, "#!/bin/sh\necho $name\n")
        makeExecutable(fixture)
        into.ok("install", "--path", fixture.toString(), "-y")
    }

    @Test
    fun subcommandsCompleteFromTheirPrefix() {
        assertEquals(listOf("uninstall", "unlink", "unpin"), complete("ketch un"))
    }

    @Test
    fun uninstallOffersInstalledPackages() {
        installLocal(sandbox, "ripgrep")
        installLocal(sandbox, "fd")
        assertEquals(listOf("ripgrep"), complete("ketch uninstall r"))
        assertEquals(listOf("fd", "ripgrep"), complete("ketch rm "))
        // Several names are allowed, so the second one completes too.
        assertEquals(listOf("ripgrep"), complete("ketch pin fd r"))
    }

    @Test
    fun aCommandThatTakesOnePackageStopsAfterIt() {
        installLocal(sandbox, "ripgrep")
        assertEquals(listOf("ripgrep"), complete("ketch info r"))
        assertFalse("ripgrep" in complete("ketch info ripgrep r"))
    }

    @Test
    fun anOptionValueIsNotCompletedAsAPackage() {
        installLocal(sandbox, "ripgrep")
        assertFalse("ripgrep" in complete("ketch upgrade --bin r"))
        assertEquals(listOf("ripgrep"), complete("ketch upgrade --bin x r"))
    }

    @Test
    fun installOffersRegistryPackages() {
        sandbox.registryPackage("ripcord", "name = \"ripcord\"\nsource = \"test:ripcord\"\n")
        installLocal(sandbox, "ripgrep")
        assertEquals(listOf("ripcord"), complete("ketch install ri"))
        assertEquals(listOf("ripcord"), complete("ketch i ri"))
    }

    @Test
    fun aRootOnTheCommandLineIsTheOneAsked() {
        val other = Sandbox().also { others += it }
        installLocal(other, "zoxide")
        assertEquals(listOf("zoxide"), complete("ketch --root ${other.root} uninstall z"))
        assertEquals(emptyList(), complete("ketch uninstall z"))
    }
}
