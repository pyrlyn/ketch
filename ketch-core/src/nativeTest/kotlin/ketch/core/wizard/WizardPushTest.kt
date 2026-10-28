package ketch.core.wizard

import fs.createDir
import fs.joinPath
import ketch.core.push.load
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The wizard's tests that need the push loader, which is native only. */
class WizardPushTest {
    private fun loadAs(
        folder: String,
        body: String,
    ) = withTempDir { dir ->
        val project = joinPath(dir, folder)
        createDir(project)
        val file = joinPath(project, "ketch.toml")
        file.write(body)
        load(file)
    }

    @Test
    fun thePushLoaderAcceptsWhatTheWizardRenders() {
        val manifest = manifest(fullAnswers)
        val body = render(manifest)
        val proposal = loadAs(manifest.name, body)
        // The body is sent to the registry verbatim, so equality here is the whole claim: what was written is what
        // loads, byte for byte.
        assertEquals(body, proposal.body)
        assertEquals(manifest.name, proposal.name)
        assertEquals(manifest.source.toString(), proposal.manifest.source.toString())
        assertEquals(manifest.kind, proposal.manifest.kind)
        assertEquals(manifest.stripPrefix, proposal.manifest.stripPrefix)
        assertEquals(listOf("*-apple-darwin.tar.gz"), proposal.manifest.asset.include)
        assertEquals("*-x86_64-apple-darwin.tar.gz", proposal.manifest.asset.target["macos-x86_64"])
    }

    @Test
    fun quotesAndBackslashesInAnAnswerSurviveTheRoundTrip() {
        val hostile = """says "hi", C:\path\to, and \ itself"""
        val answers = minimalAnswers("tool", "github:acme/tool").copy(description = hostile)
        val back = loadAs("tool", render(manifest(answers)))
        assertEquals(hostile, back.manifest.description)
    }
}
