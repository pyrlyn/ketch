package ketch.core.importer

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private val MIXED =
    """
    PackageIdentifier: Some.Tool
    PackageVersion: 1.2.0
    InstallerType: zip
    NestedInstallerType: portable
    NestedInstallerFiles:
    - RelativeFilePath: tool.exe
    Installers:
    - Architecture: x64
      InstallerUrl: https://github.com/some/tool/releases/download/v1.2.0/tool-1.2.0-x64.zip
      InstallerSha256: 0000000000000000000000000000000000000000000000000000000000000000
    - Architecture: arm64
      InstallerUrl: https://downloads.example.com/tool/1.2.0/tool-arm64.zip
      InstallerSha256: 1111111111111111111111111111111111111111111111111111111111111111
    ManifestType: installer
    ManifestVersion: 1.12.0
    """.trimIndent() + "\n"

private val CHOICES =
    """
    PackageIdentifier: Some.Tool
    PackageVersion: 2.0
    Installers:
    - Architecture: x64
      InstallerType: msi
      InstallerUrl: https://github.com/some/tool/releases/download/v2.0/tool-2.0-x64.msi
    - Architecture: x64
      InstallerType: zip
      NestedInstallerType: portable
      NestedInstallerFiles:
      - RelativeFilePath: tool-2.0/tool-cli.exe
        PortableCommandAlias: tool
      InstallerLocale: de-DE
      InstallerUrl: https://github.com/some/tool/releases/download/v2.0/tool-2.0-x64-de.zip
    - Architecture: x64
      InstallerType: zip
      NestedInstallerType: portable
      NestedInstallerFiles:
      - RelativeFilePath: tool-2.0/tool-cli.exe
        PortableCommandAlias: tool
      InstallerLocale: en-US
      InstallerUrl: https://github.com/some/tool/releases/download/v2.0/tool-2.0-x64.zip
      InstallerSha256: ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB
    ManifestType: installer
    ManifestVersion: 1.12.0
    """.trimIndent() + "\n"

private val TWO_ZIPS =
    """
    PackageIdentifier: Some.Tool
    PackageVersion: 3.0.0
    InstallerType: portable
    Installers:
    - Architecture: x64
      InstallerUrl: https://github.com/some/tool/releases/download/v3.0.0/tool-gnu.exe
    - Architecture: x64
      InstallerUrl: https://github.com/some/tool/releases/download/v3.0.0/tool-msvc.exe
    """.trimIndent() + "\n"

private val PORTABLE_NEUTRAL =
    """
    PackageIdentifier: Some.Script
    PackageVersion: 0.4.1
    InstallerType: portable
    Commands:
    - some-script
    Installers:
    - Architecture: neutral
      InstallerUrl: https://github.com/some/script/releases/latest/download/some-script.exe
    """.trimIndent() + "\n"

class WingetTest {
    private fun fixture(name: String) = fixture("winget", name)

    private fun convertFixture(id: String) = convertWinget(fixture("$id.installer.yaml"))

    private fun converted(verdict: Conversion) = verdict as? Converted ?: fail("$verdict")

    private fun unsupported(verdict: Conversion) = (verdict as? Rejected.Unsupported ?: fail("$verdict")).why

    private val endpoints = Endpoints(wingetApi = "https://api.test/m", wingetRaw = "https://raw.test/m")

    private fun failure(
        fetch: Fetch,
        id: String,
    ) = assertFailsWith<KetchError> { lookupWinget(fetch, endpoints, id) }.message.orEmpty()

    @Test
    fun aPortableZipConvertsPerArchitectureAndSkipsX86() {
        val c = converted(convertFixture("BurntSushi.ripgrep.MSVC"))
        assertEquals("ripgrep", c.name)
        assertEquals("BurntSushi/ripgrep", c.repo)
        assertEquals("15.2.0", c.tag)
        assertEquals(listOf("windows-aarch64", "windows-x86_64"), c.artifacts.keys.toList())
        assertEquals("71b2fef860abe467217a538ff31de02f5258807c0129f771846f87bd029aafc5", c.artifacts.getValue("windows-x86_64").sha256, "lowercased")
        val expected =
            """
            # Written by `ketch import winget BurntSushi.ripgrep.MSVC`. Schema: docs/MANIFESTS.md.
            name = "ripgrep"
            source = "github:BurntSushi/ripgrep"

            bin = [{ name = "rg" }]

            [asset.target]
            "windows-aarch64" = "ripgrep-*-aarch64-pc-windows-msvc.zip"
            "windows-x86_64" = "ripgrep-*-x86_64-pc-windows-msvc.zip"
            """
        assertEquals(expected.trimIndent(), c.render().trimEnd('\n'))
    }

    @Test
    fun rootLevelNestedFilesApplyToEveryInstaller() {
        val c = converted(convertFixture("JesseDuffield.lazygit"))
        assertEquals("lazygit", c.name)
        assertEquals("v0.65.1", c.tag)
        assertEquals("lazygit", c.bins[0].name)
        assertEquals(
            "lazygit_*_Windows_x86_64.zip",
            c
                .manifest()
                .asset.target
                .getValue("windows-x86_64"),
        )
    }

    @Test
    fun anInnoInstallerOnGithubIsRefusedForItsType() {
        // Two installers per architecture (user and machine scope) with the same URL: one download, and the refusal
        // names the type.
        val why = unsupported(convertFixture("Git.Git"))
        assertTrue("`inno`" in why, why)
    }

    @Test
    fun aVendorHostedInstallerIsRefusedByTheHardRule() {
        assertEquals(Rejected.NotGithubReleases, convertFixture("Google.Chrome"))
    }

    @Test
    fun oneArchitectureOffGithubRejectsThePackage() {
        assertEquals(Rejected.NotGithubReleases, convertWinget(MIXED))
    }

    @Test
    fun ofSeveralInstallersThePlaceableEnglishOneIsTaken() {
        val c = converted(convertWinget(CHOICES))
        assertEquals("2.0", c.version, "a YAML number is still the version text")
        assertEquals("tool-2.0-x64.zip", c.artifacts.getValue("windows-x86_64").file)
        assertEquals("tool", c.bins[0].name)
        assertEquals("*tool-cli.exe", c.bins[0].path)
    }

    @Test
    fun twoEquallyGoodInstallersForOneTargetAreRefusedByName() {
        val why = unsupported(convertWinget(TWO_ZIPS))
        assertTrue("tool-gnu.exe" in why && "tool-msvc.exe" in why, why)
    }

    @Test
    fun aNeutralPortableCoversBothWindowsTargetsWithItsCommand() {
        val c = converted(convertWinget(PORTABLE_NEUTRAL))
        assertNull(c.tag, "a latest/download URL names no tag")
        assertEquals(2, c.artifacts.size)
        assertEquals("some-script", c.bins[0].name)
        assertNull(c.artifacts.getValue("windows-x86_64").sha256)
    }

    @Test
    fun theNewestVersionFolderIsReadNumerically() {
        val f =
            Recorded()
                .with("https://api.test/m/g/Git/Git", fixture("list-Git.Git.json"))
                .with("https://raw.test/m/g/Git/Git/2.55.0.5/Git.Git.installer.yaml", fixture("Git.Git.installer.yaml"))
        // 2.55.0.5 sorts after 2.55.0 and 2.9.x; reaching the inno refusal proves that is the manifest that was read.
        val err = failure(f, "Git.Git")
        assertTrue(err.startsWith("Git.Git can't be converted: it installs with a winget `inno`"), err)
    }

    @Test
    fun subpackageFoldersAreNotVersions() {
        val f =
            Recorded()
                .with("https://api.test/m/g/Google/Chrome", fixture("list-Google.Chrome.json"))
                .with("https://raw.test/m/g/Google/Chrome/154.0.8037.93/Google.Chrome.installer.yaml", fixture("Google.Chrome.installer.yaml"))
        assertEquals(notGithubMessage("Google.Chrome"), failure(f, "Google.Chrome"))
    }

    @Test
    fun aSingletonManifestIsReadWhenThereIsNoInstallerFile() {
        val f =
            Recorded()
                .with("https://api.test/m/s/Some/Tool", """[{"name":"1.2.0","type":"dir"}]""")
                .with("https://raw.test/m/s/Some/Tool/1.2.0/Some.Tool.yaml", MIXED)
        assertEquals(notGithubMessage("Some.Tool"), failure(f, "Some.Tool"))
    }

    @Test
    fun anUnknownIdentifierSaysIdentifiersAreCaseSensitive() {
        val err = failure(Recorded(), "burntsushi.ripgrep.msvc")
        assertTrue("case-sensitive" in err, err)
        val bare = failure(Recorded(), "ripgrep")
        assertTrue("Publisher.Package" in bare, bare)
    }

    @Test
    fun theWholeLookupReachesTheConvertedPackage() {
        val f =
            Recorded()
                .with("https://api.test/m/b/BurntSushi/ripgrep/MSVC", fixture("list-BurntSushi.ripgrep.MSVC.json"))
                .with(
                    "https://raw.test/m/b/BurntSushi/ripgrep/MSVC/15.2.0/BurntSushi.ripgrep.MSVC.installer.yaml",
                    fixture("BurntSushi.ripgrep.MSVC.installer.yaml"),
                )
        assertEquals("15.2.0", lookupWinget(f, endpoints, "BurntSushi.ripgrep.MSVC").converted.version)
    }

    @Test
    fun versionFoldersOrderLikeWingetOrdersThem() {
        assertTrue(compareVersions("2.55.0.5", "2.55.0") > 0)
        assertTrue(compareVersions("2.10.0", "2.9.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
    }
}
