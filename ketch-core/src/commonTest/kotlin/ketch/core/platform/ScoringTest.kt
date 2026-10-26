package ketch.core.platform

import ketch.core.model.Arch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun tokenMatchingRespectsWordBoundaries() {
        // The whole reason `contains` is not good enough.
        assertFalse(tokenAt("x86_64-apple-darwin", "win"))
        assertTrue(tokenAt("tool-windows-amd64.zip", "windows"))
        assertFalse(tokenAt("tool-install.tar.gz", "all"))
        assertTrue(tokenAt("tool-universal-all.zip", "all"))
    }

    @Test
    fun isRejectedWithoutExtraTokensRejectsSidecarsAndForeignFormats() {
        for (name in listOf("rg-14.1.0-aarch64-apple-darwin.tar.gz.sha256", "checksums.txt", "ripgrep_14.1.0_amd64.deb")) {
            assertTrue(isRejected(name.lowercase(), emptyList()), "should have rejected $name")
        }
    }

    @Test
    fun isRejectedWithExtraTokensRejectsByproducts() = assertTrue(isRejected("tool-macos-arm64.dsym.zip", listOf("dsym")))

    @Test
    fun isRejectedMatchesNonBinaryTokensAsWholeParts() {
        assertFalse(isRejected("tool-1.0-macos-arm64-resources.tar.gz", emptyList()), "resources must not match the sources token")
        assertTrue(isRejected("tool-1.0-sources.tar.gz", emptyList()), "sources tarball must be rejected")
        assertTrue(isRejected("tool-src-linux-amd64.tar.gz", emptyList()), "src build must be rejected")
    }

    @Test
    fun containerBonusPrefersTarGzOverZip() = assertTrue(containerBonus("tool-1.0-aarch64-apple-darwin.tar.gz").first > containerBonus("tool-1.0-aarch64-apple-darwin.zip").first)

    @Test
    fun recognisesBuildMetadataInABinaryName() {
        assertTrue(looksLikeBuildArtifact("jq-macos-arm64"))
        assertTrue(looksLikeBuildArtifact("jq-linux-amd64"))
        assertTrue(looksLikeBuildArtifact("tool-x86_64-pc-windows-msvc.exe"))
        assertTrue(looksLikeBuildArtifact("tool-v1.2.3"))
        assertFalse(looksLikeBuildArtifact("rg"))
        assertFalse(looksLikeBuildArtifact("fd"))
    }

    @Test
    fun rejectsForeignPlatformsAndSidecars() {
        for (name in listOf(
            "rg-14.1.0-x86_64-unknown-linux-musl.tar.gz",
            "tool-windows-amd64.zip",
            "rg-14.1.0-aarch64-apple-darwin.tar.gz.sha256",
            "ripgrep_14.1.0_amd64.deb",
            "checksums.txt",
            "tool-macos-arm64.dSYM.zip",
        )) {
            assertNull(scoreMacosAsset(name, Arch.Aarch64, true), "should have rejected $name")
        }
    }

    @Test
    fun prefersTheNativeArchitectureOverEmulation() {
        val native = assertNotNull(scoreMacosAsset("rg-14.1.0-aarch64-apple-darwin.tar.gz", Arch.Aarch64, true))
        val rosetta = assertNotNull(scoreMacosAsset("rg-14.1.0-x86_64-apple-darwin.tar.gz", Arch.Aarch64, true))
        assertTrue(native.score > rosetta.score)
        assertTrue(rosetta.emulated && !native.emulated)
        // Emulation is a choice, not a default the user cannot refuse.
        assertNull(scoreMacosAsset("rg-14.1.0-x86_64-apple-darwin.tar.gz", Arch.Aarch64, false))
    }

    @Test
    fun universalBuildsAreAcceptedOnAnyMac() {
        val universal = assertNotNull(scoreMacosAsset("tool-1.0-universal2-apple-darwin.tar.gz", Arch.Aarch64, true))
        assertEquals(Arch.Universal, universal.arch)
        assertFalse(universal.emulated)
    }

    @Test
    fun namesWithoutAnOsStillQualifyButRankLower() {
        val explicit = assertNotNull(scoreMacosAsset("tool_1.0_darwin_arm64.tar.gz", Arch.Aarch64, true))
        val bare = assertNotNull(scoreMacosAsset("tool_1.0_arm64.tar.gz", Arch.Aarch64, true))
        assertTrue(explicit.score > bare.score)
    }

    @Test
    fun reasonsReadAsRustKetchShowsThem() {
        assertEquals("darwin / aarch64 / tar.gz", scoreMacosAsset("rg-14.1.0-aarch64-apple-darwin.tar.gz", Arch.Aarch64, true)?.reason)
        assertEquals("linux / gnu / x86_64 / tar.gz", scoreLinuxAsset("rg-x86_64-unknown-linux-gnu.tar.gz", Arch.X86_64, true, false)?.reason)
    }

    @Test
    fun linuxRejectsForeignPlatformsAndSidecars() {
        for (name in listOf(
            "rg-14.1.0-aarch64-apple-darwin.tar.gz",
            "tool-windows-amd64.zip",
            "rg-14.1.0-x86_64-unknown-linux-musl.tar.gz.sha256",
            "ripgrep_14.1.0_amd64.deb",
            "tool.exe",
        )) {
            assertNull(scoreLinuxAsset(name, Arch.X86_64, true, false), "should have rejected $name")
        }
    }

    @Test
    fun linuxPrefersNativeGnuOverMuslOnAGnuHost() {
        val gnu = assertNotNull(scoreLinuxAsset("rg-14.1.0-x86_64-unknown-linux-gnu.tar.gz", Arch.X86_64, true, false))
        val musl = assertNotNull(scoreLinuxAsset("rg-14.1.0-x86_64-unknown-linux-musl.tar.gz", Arch.X86_64, true, false))
        assertTrue(gnu.score > musl.score)
        assertTrue(!gnu.emulated && !musl.emulated)
    }

    @Test
    fun linuxMuslHostRefusesGnuAndAcceptsMusl() {
        assertNull(scoreLinuxAsset("rg-14.1.0-x86_64-unknown-linux-gnu.tar.gz", Arch.X86_64, true, true))
        assertNotNull(scoreLinuxAsset("rg-14.1.0-x86_64-unknown-linux-musl.tar.gz", Arch.X86_64, true, true))
    }

    @Test
    fun linuxEmulatedX8664IsOptionalOnAarch64() {
        val native = assertNotNull(scoreLinuxAsset("rg-14.1.0-aarch64-unknown-linux-gnu.tar.gz", Arch.Aarch64, true, false))
        val emulated = assertNotNull(scoreLinuxAsset("rg-14.1.0-x86_64-unknown-linux-gnu.tar.gz", Arch.Aarch64, true, false))
        assertTrue(native.score > emulated.score)
        assertTrue(emulated.emulated && !native.emulated)
        assertNull(scoreLinuxAsset("rg-14.1.0-x86_64-unknown-linux-gnu.tar.gz", Arch.Aarch64, false, false))
    }

    @Test
    fun windowsRejectsUnixAssetsAndInstallers() {
        for (name in listOf(
            "rg-14.1.0-x86_64-unknown-linux-gnu.tar.gz",
            "rg-14.1.0-aarch64-apple-darwin.tar.gz",
            "tool-x86_64-unknown-linux-musl.tar.gz",
            "setup.msi",
            "tool.nupkg",
        )) {
            assertNull(scoreWindowsAsset(name, Arch.X86_64), "should have rejected $name")
        }
    }

    @Test
    fun windowsAcceptsExeAndMingwTriples() {
        val exe = assertNotNull(scoreWindowsAsset("rg.exe", Arch.X86_64))
        val mingw = assertNotNull(scoreWindowsAsset("rg-14.1.0-x86_64-pc-windows-gnu.zip", Arch.X86_64))
        val msvc = assertNotNull(scoreWindowsAsset("rg-14.1.0-x86_64-pc-windows-msvc.zip", Arch.X86_64))
        assertEquals(Arch.X86_64, exe.arch)
        assertFalse(mingw.emulated)
        assertTrue(msvc.score > exe.score)
    }

    @Test
    fun windowsPrefersNativeArchitecture() {
        assertNull(scoreWindowsAsset("tool-aarch64-pc-windows-msvc.zip", Arch.X86_64))
        assertNotNull(scoreWindowsAsset("tool-x86_64-pc-windows-msvc.zip", Arch.X86_64))
        // cargo-dist name used by pyrlyn/rtok releases (ketch-registry include).
        assertNotNull(scoreWindowsAsset("rtok-x86_64-pc-windows-msvc.zip", Arch.X86_64))
    }

    @Test
    fun foreignOperatingSystemsAreNeverSelected() {
        for (name in listOf("tool-1.0-x86_64-unknown-freebsd.tar.gz", "tool-1.0-amd64-netbsd.tar.gz", "tool-1.0-x86_64-plan9.tar.gz")) {
            assertNull(scoreMacosAsset(name, Arch.Aarch64, true), "macOS should have rejected $name")
            assertNull(scoreLinuxAsset(name, Arch.Aarch64, true, false), "Linux should have rejected $name")
            assertNull(scoreWindowsAsset(name, Arch.X86_64), "Windows should have rejected $name")
        }
    }

    @Test
    fun thePackagesOwnBinaryIsOrderedFirst() {
        assertEquals(listOf("d/rtok.exe", "d/rtok-hook.exe"), orderDiscoveredExecutables(listOf("d/rtok-hook.exe", "d/rtok.exe"), "rtok"))
        assertEquals(listOf("d/a", "d/b"), orderDiscoveredExecutables(listOf("d/b", "d/a"), "rtok"))
    }
}
