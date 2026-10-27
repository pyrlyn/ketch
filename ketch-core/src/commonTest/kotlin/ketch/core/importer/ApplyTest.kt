package ketch.core.importer

import ketch.core.error.KetchError
import ketch.core.model.Arch
import ketch.core.model.BinSpec
import ketch.core.model.Os
import ketch.core.model.PackageKind
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private val MAC = TargetSpec(Os.MacOs, Arch.Aarch64)
private val LINUX = TargetSpec(Os.Linux, Arch.X86_64)

class ApplyTest {
    private fun converted(
        tag: String?,
        sha: String?,
    ) = Converted(
        Backend.Brew,
        "fly",
        "fly",
        "1.2.0",
        "superfly/flyctl",
        tag,
        PackageKind.Auto,
        listOf(BinSpec(path = null, name = "fly")),
        mapOf(MAC.toString() to Artifact("fly-1.2.0-darwin-arm64.tar.gz", sha)),
    )

    private fun Converted.renamed(bin: String) = copy(bins = listOf(BinSpec(path = null, name = bin)))

    private fun apply(step: Step) = step as? Apply ?: fail("expected changes, got up to date")

    private fun have(tag: String) = Have(Version.parse(tag), tag)

    @Test
    fun aFirstImportWritesTheFileAndInstallsThePinnedAsset() {
        val c = converted("v1.2.0", "ab")
        val a = apply(decide(c, c.render(), null, null, MAC, "fly.toml"))
        assertEquals(c.render(), a.write)
        assertEquals(VersionSpec.Exact("v1.2.0"), a.version)
        assertFalse(a.force)
        assertEquals("fly-1.2.0-darwin-arm64.tar.gz", a.asset)
        assertEquals("ab", a.sha256)
        assertFalse(a.unchecked)
    }

    @Test
    fun theSameFileAndTheSameVersionInstalledIsUpToDate() {
        val c = converted("v1.2.0", "ab")
        val text = c.render()
        assertEquals(Step.UpToDate, decide(c, text, text, have("v1.2.0"), MAC, "fly.toml"))
    }

    @Test
    fun aNewUpstreamVersionRewritesTheFileAndUpgrades() {
        val old = converted("v1.1.0", "aa")
        val new = converted("v1.2.0", "ab")
        val a = apply(decide(new, new.render(), old.render(), have("v1.1.0"), MAC, "fly.toml"))
        assertEquals(VersionSpec.Exact("v1.2.0"), a.version)
        assertFalse(a.force, "a new version needs no force")
    }

    @Test
    fun anUnchangedFileWithAnOlderInstallUpgradesWithoutWriting() {
        val c = converted("v1.2.0", "ab")
        val text = c.render()
        val a = apply(decide(c, text, text, have("v1.0.0"), MAC, "fly.toml"))
        assertNull(a.write)
        assertEquals(VersionSpec.Exact("v1.2.0"), a.version)
    }

    @Test
    fun aChangedManifestAtTheInstalledVersionReinstallsIt() {
        val c = converted("v1.2.0", "ab")
        val a = apply(decide(c, c.render(), c.renamed("flyctl").render(), have("v1.2.0"), MAC, "fly.toml"))
        assertNotNull(a.write)
        assertTrue(a.force)
    }

    @Test
    fun anInstallNewerThanTheCatalogueIsKeptAndOnlyRelinked() {
        val c = converted("v1.2.0", "ab")
        val a = apply(decide(c, c.render(), c.renamed("flyctl").render(), have("v2.0.0"), MAC, "fly.toml"))
        assertEquals(VersionSpec.Exact("v2.0.0"), a.version)
        assertTrue(a.force)
        assertNull(a.asset, "the catalogue's file is for another version")
    }

    @Test
    fun anInstallNewerThanTheCatalogueWithTheSameFileIsUpToDate() {
        val c = converted("v1.2.0", "ab")
        val text = c.render()
        assertEquals(Step.UpToDate, decide(c, text, text, have("v2.0.0"), MAC, "fly.toml"))
    }

    @Test
    fun aFileSomebodyWroteByHandIsRefused() {
        val c = converted("v1.2.0", "ab")
        val err = assertFailsWith<KetchError> { decide(c, c.render(), "name = \"fly\"\nrepo = \"me/fly\"\n", null, MAC, "/m/fly.toml") }.message.orEmpty()
        assertTrue("/m/fly.toml was not written by `ketch import`" in err, err)
    }

    @Test
    fun aHostTheSourceHasNoBuildForIsRefusedBeforeWriting() {
        val c = converted("v1.2.0", "ab")
        val err = assertFailsWith<KetchError> { decide(c, c.render(), null, null, LINUX, "fly.toml") }.message.orEmpty()
        assertTrue("has no download for linux-x86_64" in err, err)
        assertTrue("macos-aarch64" in err, err)
    }

    @Test
    fun aMissingChecksumIsFlaggedAndNotInvented() {
        val c = converted("v1.2.0", null)
        val a = apply(decide(c, c.render(), null, null, MAC, "fly.toml"))
        assertTrue(a.unchecked)
        assertNull(a.sha256)
    }

    @Test
    fun aLatestDownloadUrlInstallsLatestWithoutTheOldChecksum() {
        val c = converted(null, "ab")
        val a = apply(decide(c, c.render(), null, null, MAC, "fly.toml"))
        assertEquals(VersionSpec.Latest, a.version)
        assertNull(a.sha256)
        assertFalse(a.unchecked)
    }
}
