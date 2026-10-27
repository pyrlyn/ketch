package ketch.core.extra

import ketch.core.error.KetchError
import ketch.core.model.ClassifiedExtra
import ketch.core.model.CompletionShell
import ketch.core.model.ExtraKind
import ketch.core.model.ExtraPath
import ketch.core.model.ExtraPathSpec
import ketch.core.model.LinkRole
import ketch.core.model.Manifest
import ketch.core.model.PackageRef
import ketch.core.tomlfile.parse
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtraTest {
    private fun classifiedPath(path: String): ClassifiedExtra = classify(ExtraPath.Plain(path))

    private fun refusal(path: String): String = assertFailsWith<KetchError> { classify(ExtraPath.Plain(path)) }.message

    @Test
    fun documentedRipgrepPathsClassify() {
        val bash = classifiedPath("complete/rg.bash")
        assertEquals(ExtraKind.Completion, bash.kind)
        assertEquals(CompletionShell.Bash, bash.shell)

        val man = classifiedPath("doc/rg.1")
        assertEquals(ExtraKind.Man, man.kind)
        assertEquals("1", man.section)

        val nested = classifiedPath("share/man/man1/rg.1")
        assertEquals(ExtraKind.Man, nested.kind)
        assertEquals("1", nested.section)
    }

    @Test
    fun gzippedManPageUnderMan1Classifies() {
        val man = classifiedPath("share/man/man8/tool.8.gz")
        assertEquals(ExtraKind.Man, man.kind)
        assertEquals("8", man.section)
    }

    @Test
    fun zshCompdefFileClassifies() {
        val zsh = classifiedPath("completions/_rg")
        assertEquals(ExtraKind.Completion, zsh.kind)
        assertEquals(CompletionShell.Zsh, zsh.shell)
    }

    @Test
    fun extensionlessFileInBashCompletionIsBash() {
        val bash = classifiedPath("share/bash-completion/completions/rg")
        assertEquals(ExtraKind.Completion, bash.kind)
        assertEquals(CompletionShell.Bash, bash.shell)
    }

    @Test
    fun rejectsAManLikeFileInACompletionDirectory() {
        val err = refusal("complete/rg.1")
        assertTrue("kind = " in err, "must refuse rather than guess: $err")
    }

    @Test
    fun rejectsABareManPageAtPayloadRoot() {
        val err = refusal("rg.1")
        assertTrue("not a man page" in err, err)
    }

    @Test
    fun rejectsLicenseAndOtherUntypedFiles() {
        val err = refusal("LICENSE")
        assertTrue("kind = " in err, err)
    }

    @Test
    fun explicitKindWinsOverPathShape() {
        val classified = classify(ExtraPath.Spec(ExtraPathSpec("misc/custom", ExtraKind.Man, section = "1")))
        assertEquals(ExtraKind.Man, classified.kind)
        assertEquals("1", classified.section)
    }

    @Test
    fun explicitCompletionWithoutShellOrSuffixIsRefused() {
        assertFailsWith<KetchError> { classify(ExtraPath.Spec(ExtraPathSpec("misc/custom", ExtraKind.Completion))) }
    }

    @Test
    fun escapingPathsAreRefused() {
        refusal("../etc/passwd")
        refusal("/etc/passwd")
    }

    @Test
    fun planMapsOntoPlatformDestinations() {
        val extras = listOf(ExtraPath.Plain("complete/rg.bash"), ExtraPath.Plain("doc/rg.1"))
        val planned =
            plan(extras, Path("/home/me/.local/share/man")) { shell ->
                if (shell == CompletionShell.Bash) Path("/home/me/.local/share/bash-completion/completions") else Path("/tmp/$shell")
            }
        assertEquals(2, planned.size)
        assertEquals(Path("/home/me/.local/share/bash-completion/completions/rg"), planned[0].dest)
        assertEquals(LinkRole.Completion, planned[0].role)
        assertEquals(Path("/home/me/.local/share/man/man1/rg.1"), planned[1].dest)
        assertEquals(LinkRole.Man, planned[1].role)
    }

    @Test
    fun extraPathsTomlAcceptsStringsAndTables() {
        val manifest =
            parse<Manifest>(
                """
                name = "rg"
                source = "github:BurntSushi/ripgrep"
                extra_paths = [
                  "complete/rg.bash",
                  { path = "misc/custom", kind = "man", section = "1" },
                ]
                """.trimIndent(),
                "ketch.toml",
            )
        manifest.validate()
        assertEquals(2, manifest.extraPaths.size)
    }

    @Test
    fun licenseAsAnExtraPathIsRefusedByValidate() {
        val manifest = Manifest.inferred(PackageRef.github("a/b")).copy(extraPaths = listOf(ExtraPath.Plain("LICENSE")))
        assertFailsWith<KetchError> { manifest.validate() }
    }
}
