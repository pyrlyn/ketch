package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.model.Arch
import ketch.core.model.Os
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ImportTest {
    @Test
    fun aReleaseDownloadUrlIsTakenApart() {
        val url =
            assertNotNull(
                releaseUrl("https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-x86_64-pc-windows-msvc.zip"),
            )
        assertEquals("BurntSushi/ripgrep", url.repo)
        assertEquals("15.2.0", url.tag)
        assertEquals("ripgrep-15.2.0-x86_64-pc-windows-msvc.zip", url.file)
    }

    @Test
    fun aLatestDownloadUrlHasNoTag() {
        val url = assertNotNull(releaseUrl("https://github.com/o/r/releases/latest/download/tool.zip"))
        assertNull(url.tag)
        assertEquals("tool.zip", url.file)
    }

    @Test
    fun anEncodedTagIsDecoded() {
        assertEquals("v1.0+build", assertNotNull(releaseUrl("https://github.com/o/r/releases/download/v1.0%2Bbuild/t.zip")).tag)
    }

    @Test
    fun anythingElseIsNotAReleaseAsset() {
        for (url in listOf(
            "https://github.com/BurntSushi/ripgrep/archive/refs/tags/15.2.0.tar.gz",
            "https://github.com/BurntSushi/ripgrep",
            "http://github.com/o/r/releases/download/v1/t.zip",
            "https://dl.google.com/chrome/mac/universal/stable/GGRO/googlechrome.dmg",
            "https://downloads.sourceforge.net/project/x/x-1.0.zip",
            "https://github.com.evil.example/o/r/releases/download/v1/t.zip",
            "https://github.com/o/r/releases/download/v1/t.zip?raw=1",
            "https://raw.githubusercontent.com/o/r/main/t.zip",
            "https://github.com/../r/releases/download/v1/t.zip",
            "https://github.com/o/r/releases/download/v1/a/t.zip",
            "https://github.com/o/r/releases/download/v1/a%2Ft.zip",
        )) {
            assertNull(releaseUrl(url), url)
        }
    }

    @Test
    fun aPrebuiltAssetNamesAnOsAndAnArchitecture() {
        for ((file, expected) in listOf(
            "ripgrep-15.2.0-x86_64-apple-darwin.tar.gz" to true,
            "fly-8.3.0-darwin-arm64.tgz" to true,
            "lazygit_0.65.1_Windows_x86_64.zip" to true,
            "jq-1.8.2.tar.gz" to false,
            "Linux-PAM-1.7.3.tar.xz" to false,
            "universal-ctags-6.2.1.tar.gz" to false,
            "source.tar.gz" to false,
            "darwin-tools-1.0.tar.gz" to false,
        )) {
            assertEquals(expected, namesAPlatform(file), file)
        }
    }

    private fun converted(
        tag: String?,
        version: String,
    ) = Converted(Backend.Brew, "x", "x", version, "o/r", tag, PackageKind.Auto, emptyList(), emptyMap())

    @Test
    fun theVersionInAnAssetNameBecomesAWildcard() {
        val cases =
            listOf(
                listOf("ripgrep-15.2.0-x86_64-pc-windows-msvc.zip", "15.2.0", "15.2.0", "ripgrep-*-x86_64-pc-windows-msvc.zip"),
                listOf("lazygit_0.65.1_Windows_x86_64.zip", "v0.65.1", "0.65.1", "lazygit_*_Windows_x86_64.zip"),
                listOf("codex-package-aarch64-apple-darwin.tar.gz", "rust-v0.160.0", "0.160.0", "codex-package-aarch64-apple-darwin.tar.gz"),
                listOf("WezTerm-macos-20240203-110809-5046fc22.zip", "20240203-110809-5046fc22", "20240203-110809,5046fc22", "WezTerm-macos-*.zip"),
                listOf("Hackintool.zip", "4.1.5", "4.1.5", "Hackintool.zip"),
                listOf("anki-26.09.3-mac-apple.dmg", "26.09.3", "26.09.3", "anki-*-mac-apple.dmg"),
                listOf("tool-v1-x86_64.tar.gz", "v1", "1", "tool-v1-x86_64.tar.gz"),
            )
        for (case in cases) assertEquals(case[3], assetGlob(case[0], converted(case[1], case[2])), case[0])
    }

    private fun target(
        os: Os,
        arch: Arch,
    ) = TargetSpec(os, arch)

    private fun assembleOne(
        backend: Backend,
        packageId: String,
        downloads: List<Download>,
    ) = assemble(backend, packageId, "x", "1.0", PackageKind.Auto, emptyList(), downloads)

    @Test
    fun oneDownloadOffGithubRejectsTheWholePackage() {
        val verdict =
            assembleOne(
                Backend.Winget,
                "X.Y",
                listOf(
                    Download(target(Os.Windows, Arch.X86_64), "https://github.com/o/r/releases/download/v1.0/r-x64.zip"),
                    Download(target(Os.Windows, Arch.Aarch64), "https://cdn.example.com/r-arm64.zip"),
                ),
            )
        assertEquals(Rejected.NotGithubReleases, verdict)
    }

    @Test
    fun downloadsFromTwoRepositoriesAreNotOneSource() {
        val verdict =
            assembleOne(
                Backend.Winget,
                "X.Y",
                listOf(
                    Download(target(Os.Windows, Arch.X86_64), "https://github.com/o/r/releases/download/v1.0/r-x64.zip"),
                    Download(target(Os.Windows, Arch.Aarch64), "https://github.com/fork/r/releases/download/v1.0/r-arm64.zip"),
                ),
            )
        assertEquals(Rejected.NotGithubReleases, verdict)
    }

    @Test
    fun downloadsFromTwoReleasesAreRefusedWithBothTags() {
        val verdict =
            assembleOne(
                Backend.Brew,
                "x",
                listOf(
                    Download(target(Os.MacOs, Arch.Aarch64), "https://github.com/o/r/releases/download/v1.1/r-arm64.zip"),
                    Download(target(Os.MacOs, Arch.X86_64), "https://github.com/o/r/releases/download/v1.0/r-x64.zip"),
                ),
            )
        val why = (verdict as? Rejected.Unsupported ?: fail("$verdict")).why
        assertTrue("v1.0, v1.1" in why, why)
    }

    @Test
    fun aForeignPackageFormatIsRefusedByName() {
        val verdict =
            assembleOne(
                Backend.Winget,
                "X.Y",
                listOf(Download(target(Os.Windows, Arch.X86_64), "https://github.com/o/r/releases/download/v1.0/r-1.0-x64.msi")),
            )
        val why = (verdict as? Rejected.Unsupported ?: fail("$verdict")).why
        assertTrue("`.msi`" in why, why)
    }

    @Test
    fun aChecksumThatIsNotSha256IsDroppedNotTrusted() {
        val d = Download(target(Os.Linux, Arch.X86_64), "https://github.com/o/r/releases/download/v1.0/r-linux-x86_64.tar.gz", "SKIP")
        val c = assembleOne(Backend.Linux, "r-bin", listOf(d)) as? Converted ?: fail("not converted")
        assertNull(c.artifacts.getValue("linux-x86_64").sha256)
    }

    @Test
    fun theHardRuleMessageIsExact() {
        assertEquals(
            "google-chrome can't be converted: it is not distributed through GitHub Releases, and that is not supported yet.",
            Rejected.NotGithubReleases.toError("google-chrome").message,
        )
    }

    @Test
    fun aNameIsCheckedBeforeItReachesAUrl() {
        for (name in listOf("BurntSushi.ripgrep.MSVC", "python@3.12", "gtk+3")) checkName(name)
        for (name in listOf("../etc", "a b", "a/b", "", "-rf", "x?y=1")) assertFailsWith<KetchError>(name) { checkName(name) }
    }

    @Test
    fun aBinEntryCarriesAPathOnlyWhenTheFileIsNamedOtherwise() {
        val same = assertNotNull(binEntry("rg", "ripgrep-15.2.0-x86_64-pc-windows-msvc\\rg.exe"))
        assertEquals("rg", same.name)
        assertNull(same.path)
        assertEquals("*tool-cli", assertNotNull(binEntry("tool", "bin/tool-cli")).path)
        val exe = assertNotNull(binEntry("tool.exe", "tool-1.0\\Tool.EXE"))
        assertEquals("tool", exe.name)
        assertNull(exe.path)
        assertNull(binEntry("  ", null))
    }
}
