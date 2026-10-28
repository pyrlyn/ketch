// `ketch config create`, the questionnaire that writes a project's `ketch.toml` (Rust tests/config_create.rs). Every
// case drives the real binary through the questionnaire's stdin contract: with a pipe attached, each question reads one
// line and an empty line takes its default; the final consent confirmation reads nothing and answers itself with its
// default. The transcript therefore decides exactly which fields the written file has.
package ketch.e2e

import fs.TempDir
import fs.createDirAll
import fs.readText
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.Run
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import process.Command
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file the all-defaults questionnaire writes in a project directory named `Fancy-Tool` for the source
 * `github:acme/fancy-tool`: the header, the two fields every manifest carries, and the `bin` entry naming the command.
 */
private const val MINIMAL_FILE =
    "# Written by `ketch config create`. Schema: docs/MANIFESTS.md.\n" +
        "name = \"fancy-tool\"\n" +
        "source = \"github:acme/fancy-tool\"\n" +
        "\n" +
        "bin = [{ name = \"fancy-tool\" }]\n"

/**
 * The empty answers that leave every question after `source` at its default: name, description, homepage, kind,
 * prereleases, strip prefix, aliases, notes, the first `bin` entry's path and name, the offer of another, extra paths,
 * asset include, asset exclude, and the asset-target loop terminator.
 */
private val REST = "\n".repeat(15)

class ConfigCreateTest {
    private val temp = TempDir.create("ketch-e2e")
    private val root = temp.path.resolve("ketch-root")
    private var inputs = 0

    @AfterTest
    fun cleanUp() = temp.close()

    private fun project(name: String = "Fancy-Tool") = temp.path.resolve(name).also(::createDirAll)

    /** The questionnaire run inside [project] with the ketch root pointed at a throwaway, [input] on stdin. */
    private fun configCreate(
        project: Path,
        input: String,
        vararg flags: String,
    ): Run {
        val stdin = temp.path.resolve("stdin-${inputs++}")
        writeText(stdin, input)
        return Command(KETCH_EXE, listOf("--root", root.toString(), "config", "create") + flags)
            .currentDir(project.toString())
            .env("NO_COLOR", "1")
            .stdinFrom(stdin.toString())
            .runOnce()
    }

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    @Test
    fun defaultsWriteOnlyNameAndSource() {
        val project = project()
        val out = configCreate(project, "github:acme/fancy-tool\n$REST", "--yes")
        assertTrue(out.success, "$out")
        assertTrue("wrote" in out.stderr, out.stderr)
        assertEquals(MINIMAL_FILE, readText(project.resolve("ketch.toml")))
    }

    /** An empty answer takes the default, so a default the name check rejects would be re-offered forever. */
    @Test
    fun aFolderNameThatIsNotAPackageNameStillGivesAUsableDefault() {
        val project = project("-Fancy-Tool-")
        val out = configCreate(project, "github:acme/fancy-tool\n$REST", "--yes")
        assertTrue(out.success, "$out")
        assertEquals(MINIMAL_FILE, readText(project.resolve("ketch.toml")))
    }

    @Test
    fun answersFillInTheOptionalFields() {
        val project = project()
        // After `source`: an empty line takes the name default, then description and homepage, then the rest.
        val out = configCreate(project, "github:acme/fancy-tool\n\nSearches files for a pattern\nhttps://acme.example/fancy-tool\n$REST", "--yes")
        assertTrue(out.success, "$out")
        val expected =
            "# Written by `ketch config create`. Schema: docs/MANIFESTS.md.\n" +
                "name = \"fancy-tool\"\n" +
                "source = \"github:acme/fancy-tool\"\n" +
                "description = \"Searches files for a pattern\"\n" +
                "homepage = \"https://acme.example/fancy-tool\"\n" +
                "\n" +
                "bin = [{ name = \"fancy-tool\" }]\n"
        assertEquals(expected, readText(project.resolve("ketch.toml")))
    }

    @Test
    fun pipedBooleanAnswersAreConsumedByTheirQuestions() {
        val project = project()
        val answers = listOf("github:acme/fancy-tool", "", "", "", "", "yes", "", "", "", "dist/fancy-tool", "", "no", "", "", "", "")
        val out = configCreate(project, answers.joinToString("\n") + "\n", "--yes")
        assertTrue(out.success, "$out")
        val expected =
            "# Written by `ketch config create`. Schema: docs/MANIFESTS.md.\n" +
                "name = \"fancy-tool\"\n" +
                "source = \"github:acme/fancy-tool\"\n" +
                "prerelease = true\n" +
                "\n" +
                "bin = [{ path = \"dist/fancy-tool\", name = \"fancy-tool\" }]\n"
        assertEquals(expected, readText(project.resolve("ketch.toml")))
    }

    @Test
    fun anExistingFileIsRefusedBeforeAnyQuestion() {
        val project = project()
        val original = "source = \"github:acme/old\"\n# do not keep me\n"
        writeText(project.resolve("ketch.toml"), original)

        // Stdin is empty: had the questionnaire started asking anyway, the first question would have failed with
        // `needs an answer` instead of the refusal.
        val out = configCreate(project, "")
        assertFalse(out.success)
        assertTrue("already exists" in out.stderr && "--force" in out.stderr && "needs an answer" !in out.stderr, out.stderr)
        assertEquals(original, readText(project.resolve("ketch.toml")))
    }

    @Test
    fun forceReplacesAnExistingFile() {
        val project = project()
        writeText(project.resolve("ketch.toml"), "source = \"github:acme/old\"\n# do not keep me\n")

        val out = configCreate(project, "github:acme/fancy-tool\n$REST", "--force", "--yes")
        assertTrue(out.success, "$out")
        assertTrue("wrote" in out.stderr, out.stderr)
        // The exact defaults file, original content gone: the whole file was replaced rather than merged.
        assertEquals(MINIMAL_FILE, readText(project.resolve("ketch.toml")))
    }

    @Test
    fun noInputAtAllLeavesTheRequiredSourceUnanswered() {
        val project = project()
        val out = configCreate(project, "", "--yes")
        assertFalse(out.success)
        assertTrue("needs an answer" in out.stderr, out.stderr)
        assertFalse(exists(project.resolve("ketch.toml")))
    }

    @Test
    fun withoutYesTheWriteConfirmationDeclinesItself() {
        val project = project()
        // A full valid transcript, but the write confirmation reads nothing from a pipe and takes its `no`.
        val out = configCreate(project, "github:acme/fancy-tool\n$REST")
        assertTrue(out.success, "$out")
        assertTrue("cancelled" in out.stderr, out.stderr)
        assertFalse(exists(project.resolve("ketch.toml")))
    }

    @Test
    fun aSourceThatDoesNotParseIsReasked() {
        val project = project()
        val out = configCreate(project, "not a repo!\nacme/fancy-tool\n$REST", "--yes")
        assertTrue(out.success, "$out")
        // The first answer drew the parse complaint rather than failing the run.
        assertTrue("is not a package reference" in out.stderr && "wrote" in out.stderr, out.stderr)
        // A bare `owner/repo` is stored with the GitHub scheme made explicit.
        assertEquals(MINIMAL_FILE, readText(project.resolve("ketch.toml")))
    }

    @Test
    fun fileWritesWhereItIsTold() {
        val project = project()
        val out = configCreate(project, "github:acme/fancy-tool\n$REST", "--file", "custom.toml", "--yes")
        assertTrue(out.success, "$out")
        assertTrue("custom.toml" in out.stderr, out.stderr)
        assertEquals(MINIMAL_FILE, readText(project.resolve("custom.toml")))
        assertFalse(exists(project.resolve("ketch.toml")))
    }

    @Test
    fun configCreateDoesNotCreateTheKetchRoot() {
        val project = project()
        assertTrue(configCreate(project, "github:acme/fancy-tool\n$REST", "--yes").success)
        assertFalse(exists(root), "config create must not mkdir the ketch root")
        assertTrue(exists(project.resolve("ketch.toml")))
    }
}
