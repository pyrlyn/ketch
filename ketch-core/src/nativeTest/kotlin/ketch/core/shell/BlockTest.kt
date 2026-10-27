package ketch.core.shell

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val BIN = "/home/u/.ketch/bin"

class BlockTest {
    private fun zshBlock() = Shell.Zsh.block(BIN)

    /** One real PATH entry per shell counts; a backup, a longer path, a comment or nothing at all does not. */
    @Test
    fun onlyARealPathEntryCountsAsConfigured() {
        val cases =
            listOf(
                "export PATH=\"/home/u/.ketch/bin:\$PATH\"" to true,
                "set -gx PATH /home/u/.ketch/bin \$PATH" to true,
                "export PATH=/home/u/.ketch/bin" to true,
                "export PATH=\"/home/u/.ketch/bin:\$PATH\"\n" to true,
                "export PATH=\"/home/u/.ketch/bin.bak:\$PATH\"" to false,
                "export PATH=\"/home/u/.ketch/bin/tools:\$PATH\"" to false,
                "  # export PATH=\"/home/u/.ketch/bin:\$PATH\"\n" to false,
                "" to false,
            )
        for ((line, expected) in cases) assertEquals(expected, mentions(line, BIN), line)
    }

    @Test
    fun aProgramPathIdentifiesTheShell() {
        val cases =
            listOf(
                "-zsh" to Shell.Zsh,
                "/bin/bash" to Shell.Bash,
                "/opt/homebrew/bin/fish" to Shell.Fish,
                "/usr/bin/tcsh" to null,
                "" to null,
                "bash.exe" to Shell.Bash,
                "C:\\Program Files\\Git\\bin\\bash.exe" to Shell.Bash,
                "C:/Program Files/Git/usr/bin/zsh.exe" to Shell.Zsh,
                "FISH.EXE" to Shell.Fish,
                "bash.Exe" to Shell.Bash,
                "C:\\Git\\usr\\bin\\zsh.eXe" to Shell.Zsh,
            )
        for ((program, expected) in cases) assertEquals(expected, Shell.fromProgram(program), program)
    }

    /** A quote or `$` in the path must not end the quoting or expand. */
    @Test
    fun aHostilePathCannotBreakPosixQuoting() {
        for (shell in listOf(Shell.Bash, Shell.Zsh)) {
            assertEquals("export PATH='/home/o'\\''brien/.ketch/bin':\"\$PATH\"", shell.export("/home/o'brien/.ketch/bin"))
            assertEquals("export PATH='/home/\$USER/bin':\"\$PATH\"", shell.export("/home/\$USER/bin"))
        }
    }

    /** The two quoters agree on what they share and differ where the shells do. */
    @Test
    fun quotingKeepsABackslashOrQuoteLiteral() {
        assertEquals("'a\\b'", quotePosix("a\\b"))
        assertEquals("'a\\\\b'", quoteFish("a\\b"))
        assertEquals("'o'\\''brien'", quotePosix("o'brien"))
        assertEquals("'o\\'brien'", quoteFish("o'brien"))
    }

    @Test
    fun aCrlfBlockIsReplacedRatherThanAppended() {
        val crlf = zshBlock().replace("\n", "\r\n")
        val original = "# mine\r\n$crlf"
        assertNotNull(blockSpan(original), original)
        val rewritten = assertNotNull(splice(original, zshBlock()), "one block, rewritten")
        assertEquals(1, Regex(Regex.escape(BEGIN)).findAll(rewritten).count(), rewritten)
        assertTrue(rewritten.startsWith("# mine\r\n"), rewritten)
        assertTrue("# <<< ketch <<<\n" in rewritten, rewritten)
        assertEquals("# mine\r\n", unsplice(original))
    }

    @Test
    fun theBlockIsAddedOnceAndThenLeftAlone() {
        val first = assertNotNull(splice("# mine\n", zshBlock()))
        assertTrue(first.startsWith("# mine\n\n"))
        assertTrue(BIN in first)
        assertNull(splice(first, zshBlock()))
    }

    @Test
    fun aMovedBinDirRewritesTheBlockInPlace() {
        val before = assertNotNull(splice("# mine\n", zshBlock()))
        val after = assertNotNull(splice(before, Shell.Zsh.block("/elsewhere/bin")))
        assertTrue("/elsewhere/bin" in after)
        assertFalse(BIN in after)
        assertEquals(1, Regex(Regex.escape(BEGIN)).findAll(after).count())
    }

    @Test
    fun removingTheBlockRestoresTheFileByteForByte() {
        val original = "# mine\nexport EDITOR=vi\n"
        assertEquals(original, unsplice(assertNotNull(splice(original, zshBlock()))))
    }

    @Test
    fun removingABlockThatWasNeverThereChangesNothing() {
        assertNull(unsplice("# mine\n"))
    }

    @Test
    fun installAndUninstallCannotGrowTheFile() {
        val original = "# mine\n"
        var text = original
        repeat(3) {
            text = splice(text, zshBlock()) ?: text
            text = unsplice(text) ?: text
        }
        assertEquals(original, text)
    }

    @Test
    fun aMarkerThatIsNotAWholeLineIsNotTheBlock() {
        // Somebody's own script that merely prints the marker.
        assertNull(blockSpan("echo \"$BEGIN here\"\n$END trailing\n"))
    }

    @Test
    fun aBlockAtTheVeryStartOfAFileIsFound() {
        assertEquals("rest\n", unsplice("${zshBlock()}rest\n"))
    }

    @Test
    fun aPathTheUserAddedByHandCountsAsConfigured() {
        assertTrue(mentions("export PATH=\"/home/u/.ketch/bin:\$PATH\"\n", BIN))
    }

    @Test
    fun aCommentedOutLineDoesNotCountAsConfigured() {
        assertFalse(mentions("  # export PATH=\"/home/u/.ketch/bin:\$PATH\"\n", BIN))
        assertFalse(mentions("", BIN))
    }

    @Test
    fun aFileWithoutATrailingNewlineStillGetsACleanBlock() {
        val text = assertNotNull(splice("# mine", zshBlock()))
        assertTrue(text.startsWith("# mine\n\n"))
        assertTrue(text.endsWith("$END\n"))
    }

    @Test
    fun anEmptyFileGetsTheBlockWithNoLeadingBlankLine() {
        assertTrue(assertNotNull(splice("", zshBlock())).startsWith(BEGIN))
    }

    /** Every shell gets a line it can actually run, quoted for its own grammar. */
    @Test
    fun eachShellGetsTheSyntaxItCanActuallyRun() {
        assertEquals("export PATH='/home/u/.ketch/bin':\"\$PATH\"", Shell.Bash.export(BIN))
        assertEquals("export PATH='/home/u/.ketch/bin':\"\$PATH\"", Shell.Zsh.export(BIN))
        assertEquals("set -gx PATH '/home/u/.ketch/bin' \$PATH", Shell.Fish.export(BIN))
    }

    /** The profile ketch created goes away whole; one the user also wrote in loses only the block. */
    @Test
    fun aProfileLosesTheBlockOrGoesAwayWhenItHeldNothingElse() {
        val block = "$BEGIN\nif (Test-Path -LiteralPath 'k.ps1') { . 'k.ps1' }\n$END\n"
        val original = "Set-PSReadLineOption -EditMode Emacs\r\n"
        assertEquals(ProfileEdit.Rewrite(original), unspliceProfile(assertNotNull(splice(original, block))))
        assertEquals(ProfileEdit.Delete, unspliceProfile(block))
        assertEquals(ProfileEdit.Delete, unspliceProfile("\uFEFF$block"))
        assertNull(unspliceProfile(original))
    }
}
