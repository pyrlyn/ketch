package ketch.cli

import cli.Command
import fs.TempDir
import fs.isDir
import fs.joinPath
import fs.readText
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManTest {
    private fun expectedNames(
        cmd: Command,
        prefix: String,
        out: MutableList<String>,
    ) {
        out += "$prefix.1"
        cmd.subcommands.filter { !it.hidden }.forEach { expectedNames(it, "$prefix-${it.name}", out) }
    }

    private fun names() = pages().map { it.fileName }

    @Test
    fun onePagePerVisibleCommand() {
        val expected = mutableListOf<String>().also { expectedNames(ketchCli(), "ketch", it) }
        assertEquals(expected, names())
    }

    @Test
    fun nestedCommandsGetTheirOwnPage() {
        val names = names()
        for (name in listOf("ketch.1", "ketch-install.1", "ketch-self-uninstall.1")) assertTrue(name in names, "missing $name: $names")
    }

    @Test
    fun hiddenCommandsAndHelpGetNoPage() {
        val names = names()
        assertFalse("ketch-man.1" in names, "$names")
        assertFalse(names.any { it.endsWith("-help.1") }, "$names")
    }

    @Test
    fun aSubcommandPageNamesItsFullInvocationAndOptions() {
        val roff = pages().first { it.fileName == "ketch-install.1" }.roff
        assertTrue(roff.contains(".TH KETCH-INSTALL 1"), roff)
        assertTrue(roff.contains("ketch install"), roff)
        assertTrue(roff.contains("\\-\\-force"), roff)
    }

    @Test
    fun tidyTurnsABreakBesideABlankLineIntoOneParagraphSpace() {
        assertEquals("help\n.sp\n\\fIPossible values:\\fR\n", tidy("help\n.br\n\n.br\n\\fIPossible values:\\fR\n"))
        assertEquals("<SHELL>\n.sp\nvalues\n", tidy("<SHELL>\n\n.br\nvalues\n"))
        for (page in pages()) assertFalse(page.roff.contains("\n\n.br\n"), page.fileName)
    }

    @Test
    fun writeToCreatesTheDirectoryAndEveryPage() {
        TempDir.create("ketch-test-").use { tmp ->
            val written = writeTo(joinPath(tmp.path, "out/man1"))
            assertEquals(pages().size, written.size)
            assertTrue(written.all { !isDir(it) && readText(it).isNotEmpty() })
        }
    }
}
