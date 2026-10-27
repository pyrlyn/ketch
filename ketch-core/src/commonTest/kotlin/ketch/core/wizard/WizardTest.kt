package ketch.core.wizard

import ketch.core.error.KetchError
import ketch.core.model.BinSpec
import ketch.core.model.ExtraPath
import ketch.core.model.PackageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WizardTest {
    /** Every field answered, in the shapes the questionnaire collects them. */
    private val full =
        Answers(
            source = "github:BurntSushi/ripgrep",
            // Typed the way a person types it; the manifest spells it the way ketch installs it.
            name = "RipGrep.rs",
            description = "Recursively search directories for a regex pattern",
            homepage = "https://github.com/BurntSushi/ripgrep",
            kind = PackageKind.Binary,
            prerelease = true,
            stripPrefix = 1uL,
            provides = listOf("rg"),
            notes = "Shell completions are under complete/ in the payload.",
            bin = listOf(BinSpec(path = "*/rg", name = "rg")),
            extraPaths = listOf("complete/rg.bash", "doc/rg.1"),
            assetInclude = listOf("*-apple-darwin.tar.gz"),
            assetExclude = listOf("*-musl-*"),
            assetTarget = mapOf("macos-x86_64" to "*-x86_64-apple-darwin.tar.gz", "macos-aarch64" to "*-aarch64-apple-darwin.tar.gz"),
        )

    private fun minimal(
        name: String,
        source: String,
    ) = Answers(source = source, name = name, bin = listOf(BinSpec(name = name)))

    @Test
    fun aFullAnswerSetMapsToTheExpectedManifest() {
        val m = manifest(full)
        assertEquals("ripgrep", m.name)
        assertEquals("github:BurntSushi/ripgrep", m.source.toString())
        assertEquals("Recursively search directories for a regex pattern", m.description)
        assertEquals("https://github.com/BurntSushi/ripgrep", m.homepage)
        assertEquals(PackageKind.Binary, m.kind)
        assertTrue(m.prerelease)
        assertEquals(1uL, m.stripPrefix)
        assertEquals(listOf("rg"), m.provides)
        assertEquals("Shell completions are under complete/ in the payload.", m.notes)
        assertEquals(listOf(BinSpec(path = "*/rg", name = "rg")), m.bin)
        assertEquals(listOf("*-apple-darwin.tar.gz"), m.asset.include)
        assertEquals(listOf("*-musl-*"), m.asset.exclude)
        assertEquals("*-aarch64-apple-darwin.tar.gz", m.asset.target["macos-aarch64"])
        assertEquals(listOf(ExtraPath.Plain("complete/rg.bash"), ExtraPath.Plain("doc/rg.1")), m.extraPaths)
    }

    @Test
    fun renderPutsEveryFieldInTheDocumentedOrder() {
        val expected =
            "# Written by `ketch config create`. Schema: docs/MANIFESTS.md.\n" +
                "name = \"ripgrep\"\n" +
                "source = \"github:BurntSushi/ripgrep\"\n" +
                "description = \"Recursively search directories for a regex pattern\"\n" +
                "homepage = \"https://github.com/BurntSushi/ripgrep\"\n" +
                "kind = \"binary\"\n" +
                "prerelease = true\n" +
                "strip_prefix = 1\n" +
                "provides = [\"rg\"]\n" +
                "notes = \"Shell completions are under complete/ in the payload.\"\n" +
                "\n" +
                "bin = [{ path = \"*/rg\", name = \"rg\" }]\n" +
                "extra_paths = [\"complete/rg.bash\", \"doc/rg.1\"]\n" +
                "\n" +
                "[asset]\n" +
                "include = [\"*-apple-darwin.tar.gz\"]\n" +
                "exclude = [\"*-musl-*\"]\n" +
                "\n" +
                "[asset.target]\n" +
                "\"macos-aarch64\" = \"*-aarch64-apple-darwin.tar.gz\"\n" +
                "\"macos-x86_64\" = \"*-x86_64-apple-darwin.tar.gz\"\n"
        assertEquals(expected, render(manifest(full)))
    }

    @Test
    fun defaultsAreOmittedAndTheSmallestFileNamesNameSourceAndBinary() {
        // A bare `owner/repo` answer must come back out as the `scheme:id` form, the way every other command reads it.
        val expected =
            "# Written by `ketch config create`. Schema: docs/MANIFESTS.md.\n" +
                "name = \"ripgrep\"\n" +
                "source = \"github:BurntSushi/ripgrep\"\n" +
                "\n" +
                "bin = [{ name = \"ripgrep\" }]\n"
        assertEquals(expected, render(manifest(minimal("ripgrep", "BurntSushi/ripgrep"))))
    }

    @Test
    fun aPackageThatLinksBinariesMustNameOne() {
        val nameless = minimal("tool", "github:acme/tool").copy(bin = emptyList())
        assertFailsWith<KetchError>("no bin entry at all") { manifest(nameless) }
        assertFailsWith<KetchError>("a glob is not a name") { manifest(nameless.copy(bin = listOf(BinSpec(path = "dist/tool*")))) }
        manifest(nameless.copy(kind = PackageKind.App))
    }

    @Test
    fun answersWhoseManifestWouldFailValidationAreRefused() {
        assertFailsWith<KetchError>("no source at all") { manifest(Answers()) }
        assertFailsWith<KetchError>("no name") { manifest(minimal("", "github:acme/tool").copy(name = "   ")) }
        assertFailsWith<KetchError>("name escapes the store") { manifest(minimal("../evil", "github:acme/tool")) }
        assertFailsWith<KetchError>("alias nobody can type") { manifest(minimal("tool", "github:acme/tool").copy(provides = listOf("two words"))) }
        assertFailsWith<KetchError>("bin entry that says nothing") { manifest(minimal("tool", "github:acme/tool").copy(bin = listOf(BinSpec()))) }
    }

    @Test
    fun kindAnswersAcceptOnlyTheThreeWords() {
        assertEquals(PackageKind.Auto, parseKind("auto"))
        assertEquals(PackageKind.App, parseKind("APP"))
        assertEquals(PackageKind.Binary, parseKind(" binary "))
        assertNull(parseKind("exe"))
    }

    @Test
    fun stripPrefixAnswersMustBeASmallWholeNumber() {
        assertNull(parseStripPrefix("0"))
        assertEquals(2uL, parseStripPrefix("2"))
        assertEquals(8uL, parseStripPrefix("8"))
        assertFailsWith<KetchError> { parseStripPrefix("9") }
        assertFailsWith<KetchError> { parseStripPrefix("one") }
        assertFailsWith<KetchError> { parseStripPrefix("-1") }
    }

    @Test
    fun listsSplitOnCommasAndDropTheBlanks() {
        assertEquals(listOf("rg", "rgr"), splitList("rg, rgr ,,"))
        assertTrue(splitList(" , ").isEmpty())
        assertTrue(splitList("").isEmpty())
    }
}
