package ketch.core.importer

import ketch.core.error.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val NOTHING = """{"resultcount":0,"results":[],"type":"multiinfo","version":5}"""

class LinuxTest {
    private fun fixture(name: String) = fixture("linux", name)

    private fun converted(verdict: Conversion) = verdict as? Converted ?: fail("$verdict")

    private val endpoints = Endpoints(arch = "https://arch.test", archGitlab = "https://gitlab.test/p", aur = "https://aur.test")

    private fun failure(
        fetch: Fetch,
        name: String,
    ) = assertFailsWith<KetchError> { lookupLinux(fetch, endpoints, name) }.message.orEmpty()

    @Test
    fun aBinPackageConvertsItsTwoKetchArchitectures() {
        val c = converted(convertSrcinfo("lazydocker-bin", fixture("aur-lazydocker-bin.SRCINFO")))
        assertEquals("lazydocker", c.name)
        assertEquals("jesseduffield/lazydocker", c.repo)
        assertEquals("v0.25.2", c.tag)
        assertEquals("lazydocker", c.bins[0].name)
        assertNull(c.bins[0].path)
        assertEquals("005c38b685aaa557e7d646d83a3dadb5024340eeed8c6a2e1949eee6f530de23", c.artifacts.getValue("linux-aarch64").sha256)
        val expected =
            """
            # Written by `ketch import linux lazydocker-bin`. Schema: docs/MANIFESTS.md.
            name = "lazydocker"
            source = "github:jesseduffield/lazydocker"

            bin = [{ name = "lazydocker" }]

            [asset.target]
            "linux-aarch64" = "lazydocker_*_Linux_arm64.tar.gz"
            "linux-x86_64" = "lazydocker_*_Linux_x86_64.tar.gz"
            """
        assertEquals(expected.trimIndent(), c.render().trimEnd('\n'))
    }

    @Test
    fun anOfficialPackageBuiltFromASourceTarballIsRefused() {
        assertEquals(Rejected.NotGithubReleases, convertSrcinfo("lazygit", fixture("arch-lazygit.SRCINFO")))
    }

    @Test
    fun aVendorDownloadIsRefusedByTheHardRule() {
        assertEquals(Rejected.NotGithubReleases, convertSrcinfo("visual-studio-code-bin", fixture("aur-visual-studio-code-bin.SRCINFO")))
    }

    @Test
    fun ofADebAndAnAppimageTheAppimageIsTakenAndLinkedByItsCommand() {
        val c = converted(convertSrcinfo("obsidian-bin", fixture("aur-obsidian.SRCINFO")))
        assertEquals("obsidian", c.name)
        assertEquals("obsidianmd/obsidian-releases", c.repo)
        assertEquals("Obsidian-1.13.7.AppImage", c.artifacts.getValue("linux-x86_64").file)
        // `obsidian-bin` is x86_64 only; its sibling `obsidian-appimage` in the same base is the one with arm64.
        assertEquals(1, c.artifacts.size)
        val manifest = c.manifest()
        assertEquals("obsidian", manifest.bin[0].name)
        assertEquals("*Obsidian-*.AppImage", manifest.bin[0].path)
        assertEquals("Obsidian-*.AppImage", manifest.asset.target.getValue("linux-x86_64"))
    }

    @Test
    fun aSplitPackageReadsItsOwnSection() {
        val c = converted(convertSrcinfo("obsidian-appimage", fixture("aur-obsidian.SRCINFO")))
        assertEquals(listOf("linux-aarch64", "linux-x86_64"), c.artifacts.keys.toList())
    }

    @Test
    fun onlyADebIsRefusedForItsFormat() {
        val srcinfo =
            "pkgbase = t-bin\n\tpkgver = 1.0\n\tarch = x86_64\n" +
                "\tsource_x86_64 = https://github.com/o/t/releases/download/v1.0/t_1.0_amd64.deb\n" +
                "\tsha256sums_x86_64 = SKIP\npkgname = t-bin\n"
        val why = (convertSrcinfo("t-bin", srcinfo) as? Rejected.Unsupported ?: fail("a .deb should not convert")).why
        assertTrue("`.deb`" in why, why)
    }

    @Test
    fun aSkippedChecksumConvertsWithNoneAndLocalFilesAreIgnored() {
        val srcinfo =
            "pkgbase = t-bin\n\tpkgver = 1.0\n\tarch = x86_64\n" +
                "\tsource = t.desktop\n\tsource = LICENSE::https://raw.githubusercontent.com/o/t/v1.0/LICENSE\n" +
                "\tsha256sums = SKIP\n\tsha256sums = SKIP\n" +
                "\tsource_x86_64 = https://github.com/o/t/releases/download/v1.0/t-linux-x86_64.tar.gz\n" +
                "\tsha256sums_x86_64 = SKIP\npkgname = t-bin\n"
        val c = converted(convertSrcinfo("t-bin", srcinfo))
        assertNull(c.artifacts.getValue("linux-x86_64").sha256)
        assertEquals(1, c.artifacts.size)
    }

    @Test
    fun anOfficialSourceBuildYieldsToTheAurBinPackage() {
        val f =
            Recorded()
                .with("https://arch.test/packages/search/json/?name=lazydocker", fixture("arch-search-lazydocker.json"))
                .with("https://gitlab.test/p/lazydocker/-/raw/main/.SRCINFO", fixture("arch-lazydocker.SRCINFO"))
                .with("https://aur.test/rpc/v5/info?arg[]=lazydocker&arg[]=lazydocker-bin", fixture("aur-info-lazydocker-bin.json"))
                .with("https://aur.test/cgit/aur.git/plain/.SRCINFO?h=lazydocker-bin", fixture("aur-lazydocker-bin.SRCINFO"))
        val found = lookupLinux(f, endpoints, "lazydocker")
        assertEquals("lazydocker-bin", found.converted.packageId)
        assertEquals(listOf("Arch [extra] lazydocker builds from source; using the AUR's lazydocker-bin"), found.notes)
    }

    @Test
    fun anOfficialSourceBuildAloneIsRefusedByTheHardRule() {
        val f =
            Recorded()
                .with("https://arch.test/packages/search/json/?name=lazygit", fixture("arch-search-lazygit.json"))
                .with("https://gitlab.test/p/lazygit/-/raw/main/.SRCINFO", fixture("arch-lazygit.SRCINFO"))
                .with("https://aur.test/rpc/v5/info?arg[]=lazygit&arg[]=lazygit-bin", NOTHING)
        assertEquals(notGithubMessage("lazygit"), failure(f, "lazygit"))
    }

    @Test
    fun packagesProvidingANameFromOneProjectResolveToTheFirst() {
        val f =
            Recorded()
                .with("https://aur.test/rpc/v5/info?arg[]=obsidian&arg[]=obsidian-bin", fixture("aur-info-obsidian-bin.json"))
                .with("https://aur.test/rpc/v5/search/obsidian?by=provides", fixture("aur-provides-obsidian.json"))
                .with("https://aur.test/cgit/aur.git/plain/.SRCINFO?h=obsidian", fixture("aur-obsidian.SRCINFO"))
        val found = lookupLinux(f, endpoints, "obsidian")
        assertEquals("obsidian-bin", found.converted.packageId)
        assertEquals(listOf("using the AUR's obsidian-bin"), found.notes)
    }

    private fun srcinfo(
        base: String,
        repo: String,
    ) = "pkgbase = $base\n\tpkgver = 1.0\n\tarch = x86_64\n" +
        "\tsource_x86_64 = https://github.com/$repo/releases/download/v1.0/tool-linux-x86_64.tar.gz\n" +
        "pkgname = $base\n"

    @Test
    fun aNameTwoProjectsProvideIsRefusedAndBothAreNamed() {
        val f =
            Recorded()
                .with("https://aur.test/rpc/v5/info?arg[]=tool&arg[]=tool-bin", NOTHING)
                .with(
                    "https://aur.test/rpc/v5/search/tool?by=provides",
                    """
                    {"results":[{"Name":"tool-bin","PackageBase":"tool-bin"},
                                {"Name":"other-tool-bin","PackageBase":"other-tool-bin"},
                                {"Name":"tool-git","PackageBase":"tool-git"}]}
                    """.trimIndent(),
                ).with("https://aur.test/cgit/aur.git/plain/.SRCINFO?h=tool-bin", srcinfo("tool-bin", "alice/tool"))
                .with("https://aur.test/cgit/aur.git/plain/.SRCINFO?h=other-tool-bin", srcinfo("other-tool-bin", "bob/tool"))
        val err = failure(f, "tool")
        assertTrue("ambiguous" in err && "other-tool-bin (bob/tool)" in err && "tool-bin (alice/tool)" in err, err)
        // Asked for by its exact name, the package is not ambiguous.
        val exact = f.with("https://aur.test/rpc/v5/info?arg[]=tool-bin", """{"results":[{"Name":"tool-bin","PackageBase":"tool-bin"}]}""")
        assertEquals("alice/tool", lookupLinux(exact, endpoints, "tool-bin").converted.repo)
    }

    @Test
    fun anUnknownNameIsReportedAsUnknown() {
        val err = failure(Recorded(), "nope")
        assertTrue("no package named `nope`" in err, err)
    }

    @Test
    fun anAurErrorIsAnErrorNotAnEmptyAnswer() {
        val f = Recorded().with("https://aur.test/rpc/v5/info?arg[]=x&arg[]=x-bin", """{"type":"error","error":"Too many package results.","results":[]}""")
        val err = failure(f, "x")
        assertTrue("Too many package results" in err, err)
    }
}
