package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.model.PackageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BrewTest {
    private fun fixture(name: String) = fixture("brew", name)

    private fun cask(name: String) = convertCask(fixture("cask-$name.json"))

    private fun converted(verdict: Conversion) = verdict as? Converted ?: fail("$verdict")

    @Test
    fun aBinaryCaskConvertsToTwoPinnedTargetsAndOneCommand() {
        val c = converted(cask("fly"))
        assertEquals("concourse/concourse", c.repo)
        assertEquals("v8.3.0", c.tag)
        assertEquals(PackageKind.Auto, c.kind)
        assertEquals(1, c.bins.size)
        assertEquals("fly", c.bins[0].name)
        assertNull(c.bins[0].path)
        assertEquals("fly-8.3.0-darwin-arm64.tgz", c.artifacts.getValue("macos-aarch64").file)
        assertEquals("fly-8.3.0-darwin-amd64.tgz", c.artifacts.getValue("macos-x86_64").file)
        assertEquals("69a33b6f3dfa9c59af96442d1bd1fe054734305e5544bd2b7dd17641234dc4cb", c.artifacts.getValue("macos-aarch64").sha256)
    }

    @Test
    fun theCaskGoldenHoldsOnlyWhatKetchNeeds() {
        val expected =
            """
            # Written by `ketch import brew fly`. Schema: docs/MANIFESTS.md.
            name = "fly"
            source = "github:concourse/concourse"

            bin = [{ name = "fly" }]

            [asset.target]
            "macos-aarch64" = "fly-*-darwin-arm64.tgz"
            "macos-x86_64" = "fly-*-darwin-amd64.tgz"
            """.trimIndent()
        assertEquals(expected, converted(cask("fly")).render().trimEnd('\n'))
    }

    @Test
    fun aBinaryInASubdirectoryKeepsItsNameAndNoPath() {
        val c = converted(cask("codex"))
        assertEquals("codex", c.bins[0].name)
        assertNull(c.bins[0].path, "bin/codex is found by its name")
        assertEquals("\"macos-x86_64\" = \"codex-package-x86_64-apple-darwin.tar.gz\"", c.render().lines().last { it.isNotEmpty() })
    }

    @Test
    fun anAppCaskBecomesAnAppWithNoBin() {
        val c = converted(cask("anki"))
        assertEquals(PackageKind.App, c.kind)
        assertTrue(c.bins.isEmpty())
        val expected =
            """
            # Written by `ketch import brew anki`. Schema: docs/MANIFESTS.md.
            name = "anki"
            source = "github:ankitects/anki"
            kind = "app"

            [asset.target]
            "macos-aarch64" = "anki-*-mac-apple.dmg"
            "macos-x86_64" = "anki-*-mac-intel.dmg"
            """.trimIndent()
        assertEquals(expected, c.render().trimEnd('\n'))
    }

    @Test
    fun anAppWithHelpersInsideTheBundleIsStillJustTheApp() {
        val c = converted(cask("wezterm"))
        assertEquals(PackageKind.App, c.kind)
        assertTrue(c.bins.isEmpty())
        // No variations: one universal download serves both architectures.
        assertEquals(c.artifacts["macos-aarch64"], c.artifacts["macos-x86_64"], "one file for both")
        assertEquals("WezTerm-macos-*.zip", c.manifest().asset.target["macos-x86_64"])
    }

    @Test
    fun aVendorCdnCaskIsRefusedByTheHardRule() {
        assertEquals(Rejected.NotGithubReleases, cask("google-chrome"))
    }

    @Test
    fun aPkgCaskOnGithubIsRefusedForItsInstaller() {
        val why = (cask("background-music") as? Rejected.Unsupported ?: fail("background-music should not convert")).why
        assertTrue("`pkg`" in why, why)
    }

    @Test
    fun aCaskWithoutAChecksumStillConvertsWithNoneRecorded() {
        val c = converted(cask("hackintool"))
        assertNull(c.artifacts.getValue("macos-aarch64").sha256, "sha256 is no_check")
        assertEquals("Hackintool.zip", c.artifacts.getValue("macos-aarch64").file)
    }

    @Test
    fun aFormulaThatBuildsFromSourceIsRefused() {
        // A release source tarball, an archive tarball, a name with a platform word.
        for (name in listOf("jq", "ripgrep", "universal-ctags")) {
            assertEquals(Rejected.NotGithubReleases, convertFormula(fixture("formula-$name.json")), name)
        }
    }

    @Test
    fun aFormulaWhoseUrlIsAPrebuiltAssetConvertsForThatTarget() {
        val c = converted(convertFormula(PREBUILT_FORMULA))
        assertEquals("o/tool", c.repo)
        assertEquals(listOf("macos-aarch64"), c.artifacts.keys.toList())
        assertEquals("a".repeat(64), c.artifacts.getValue("macos-aarch64").sha256)
        assertEquals("tool", c.bins[0].name)
    }

    private val endpoints = Endpoints(brew = "https://brew.test/api")

    @Test
    fun anUnknownNameSaysSoAndNamesBothHalves() {
        val f = Recorded()
        assertEquals("Homebrew has no formula or cask named `nope`", assertFailsWith<KetchError> { lookupBrew(f, endpoints, "nope", BrewPick.Either) }.message)
        assertEquals("Homebrew has no cask named `nope`", assertFailsWith<KetchError> { lookupBrew(f, endpoints, "nope", BrewPick.Cask) }.message)
    }

    @Test
    fun aNameThatIsBothTakesTheHalfThatConverts() {
        val f =
            Recorded()
                .with("https://brew.test/api/formula/fly.json", fixture("formula-jq.json"))
                .with("https://brew.test/api/cask/fly.json", fixture("cask-fly.json"))
        val found = lookupBrew(f, endpoints, "fly", BrewPick.Either)
        assertEquals("concourse/concourse", found.converted.repo)
        assertEquals(1, found.notes.size)
        assertTrue("using the cask" in found.notes[0], "${found.notes}")
    }

    @Test
    fun aNameThatIsBothAndConvertsBothWaysMustBePicked() {
        val f =
            Recorded()
                .with("https://brew.test/api/formula/tool.json", PREBUILT_FORMULA)
                .with("https://brew.test/api/cask/tool.json", fixture("cask-fly.json"))
        val err = assertFailsWith<KetchError> { lookupBrew(f, endpoints, "tool", BrewPick.Either) }
        assertTrue("--formula or --cask" in err.message.orEmpty(), err.message)
        assertEquals("o/tool", lookupBrew(f, endpoints, "tool", BrewPick.Formula).converted.repo)
        assertEquals("concourse/concourse", lookupBrew(f, endpoints, "tool", BrewPick.Cask).converted.repo)
    }

    @Test
    fun aFormulaOnlyNameIsRefusedWithTheHardRule() {
        val f = Recorded().with("https://brew.test/api/formula/jq.json", fixture("formula-jq.json"))
        assertEquals(notGithubMessage("jq"), assertFailsWith<KetchError> { lookupBrew(f, endpoints, "jq", BrewPick.Either) }.message)
    }

    @Test
    fun aHostileNameNeverReachesAUrl() {
        assertFailsWith<KetchError> { lookupBrew(Recorded(), endpoints, "../../x", BrewPick.Either) }
    }

    private companion object {
        const val PREBUILT_FORMULA = """{
            "name": "tool",
            "versions": { "stable": "2.0.1" },
            "urls": { "stable": {
                "url": "https://github.com/o/tool/releases/download/v2.0.1/tool-2.0.1-aarch64-apple-darwin.tar.gz",
                "checksum": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            } }
        }"""
    }
}
