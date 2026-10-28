package ketch.cli

import cli.CompletionShell
import fs.TempDir
import fs.isDir
import fs.joinPath
import fs.readText
import ketch.core.extra.classify
import ketch.core.extra.writeKetchDocs
import ketch.core.model.ExtraKind
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfDocsTest {
    private fun <T> withPrefix(body: (Path) -> T): T = TempDir.create("ketch-test-").use { body(joinPath(it.path, "store/ketch/1.0.0")) }

    private fun isFile(path: Path) = !isDir(path) && readText(path).isNotEmpty()

    @Test
    fun generatedKetchDocsClassifyAndStayInThePrefix() =
        withPrefix { prefix ->
            val extras = writeKetchDocs(prefix, SELF_DOCS)
            assertTrue(isFile(joinPath(prefix, "share/man/man1/ketch.1")))
            assertTrue(isFile(joinPath(prefix, "share/ketch/completions/ketch")))
            extras.forEach(::classify)
            val man = readText(joinPath(prefix, "share/man/man1/ketch.1"))
            assertTrue(man.contains(".TH KETCH 1"), man)
            assertTrue(man.contains(".SH SUBCOMMANDS"), man)
        }

    @Test
    fun everyGeneratedManPageIsRecordedAsAManExtra() =
        withPrefix { prefix ->
            val recorded = writeKetchDocs(prefix, SELF_DOCS).map(::classify).filter { it.kind == ExtraKind.Man }.map { it.relPath }
            assertEquals(pages().map { "share/man/man1/${it.fileName}" }, recorded)
            assertTrue(recorded.all { isFile(joinPath(prefix, it)) })
        }

    @Test
    fun theScriptSelfInstallWritesIsTheOneCompletionsPrints() =
        withPrefix { prefix ->
            writeKetchDocs(prefix, SELF_DOCS)
            assertEquals(script(CompletionShell.Bash), readText(joinPath(prefix, "share/ketch/completions/ketch")))
        }
}
