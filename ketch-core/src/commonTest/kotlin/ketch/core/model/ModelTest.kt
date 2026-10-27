package ketch.core.model

import ketch.core.config.REGISTRY_REPO
import ketch.core.config.SELF_REPO
import ketch.core.error.KetchError
import ketch.core.tomlfile.parse
import ketch.core.tomlfile.render
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    private val base = Manifest.inferred(PackageRef.github("a/b"))

    @Test
    fun aReferenceToAMovedRepositoryReadsAsItsNewName() {
        for (old in listOf("listepo/ketch", "github:listepo/ketch", "GitHub:Listepo/Ketch", "pyrlyn/ketch", "github:pyrlyn/ketch")) {
            assertEquals(PackageRef.github(SELF_REPO), PackageRef.parse(old), old)
        }
        assertEquals(PackageRef.github(REGISTRY_REPO), PackageRef.parse("github:listepo/ketch-registry"))
        // Only the repositories that moved: the rest of the account stays.
        assertEquals(PackageRef.github("listepo/swarfr"), PackageRef.parse("listepo/swarfr"))
        // A stored state entry is read through the same path.
        val stored = Json.decodeFromString<PackageRef>("\"github:listepo/ketch\"")
        assertEquals("github:pyrlyn/ketch", stored.toString())
    }

    @Test
    fun validateRefusesNamesThatWouldEscapeTheirDirectory() {
        base.validate()
        val bad =
            listOf(
                base.copy(name = "../evil"),
                base.copy(bin = listOf(BinSpec(name = "../../.zshrc"))),
                base.copy(bin = listOf(BinSpec(name = "safe‮sudo"))),
                base.copy(bin = listOf(BinSpec(name = "x", path = "../../../x"))),
                base.copy(bin = listOf(BinSpec())),
                base.copy(provides = listOf("two words")),
            )
        for (manifest in bad) assertFailsWith<KetchError>(manifest.toString()) { manifest.validate() }
    }

    @Test
    fun vPrefixDoesNotMakeEqualSemverVersionsOrderApart() {
        val plain = Version.parse("0.4.0")
        val tagged = Version.parse("v0.4.0")
        assertEquals(0, plain.compareTo(tagged))
        assertEquals(0, tagged.compareTo(plain))
        assertTrue(tagged <= plain && plain <= tagged)
    }

    @Test
    fun parsesBareRepoAsGithub() {
        val r = assertNotNull(PackageRef.parse("BurntSushi/ripgrep"))
        assertEquals("github", r.scheme)
        assertEquals("BurntSushi/ripgrep", r.id)
        assertEquals("ripgrep", r.shortName)
    }

    @Test
    fun bareWordIsAnAliasNotARef() {
        assertNull(PackageRef.parse("ripgrep"))
        assertEquals("ripgrep", PackageSpec.parse("ripgrep").alias)
    }

    @Test
    fun parsesExplicitScheme() {
        val r = assertNotNull(PackageRef.parse("gitlab:group/proj"))
        assertEquals("gitlab", r.scheme)
        assertEquals("group/proj", r.id)
    }

    @Test
    fun splitsVersionAfterLastSlashOnly() {
        val s = PackageSpec.parse("BurntSushi/ripgrep@14.1.0")
        assertEquals("BurntSushi/ripgrep", s.reference?.id)
        assertEquals(VersionSpec.Exact("14.1.0"), s.version)
        assertEquals(VersionSpec.Latest, PackageSpec.parse("cli/cli").version)
    }

    @Test
    fun versionsOrderBySemverThenNaturally() {
        assertTrue(Version.parse("v1.10.0") > Version.parse("v1.9.0"))
        assertTrue(Version.parse("2024.10.1") > Version.parse("2024.9.30"))
        assertTrue(Version.parse("1.0.0") > Version.parse("1.0.0-beta.1"))
        // No digits at all: fall back to natural comparison.
        assertTrue(Version.parse("nightly-b") > Version.parse("nightly-a"))
    }

    @Test
    fun relaxedVersionsParse() {
        assertTrue(Version.parse("v1.2").isSemver)
        assertTrue(Version.parse("3").isSemver)
        assertTrue(Version.parse("1.2.3.4").isSemver)
    }

    @Test
    fun versionsThatDifferOnlyPastSemverStillOrder() {
        // Both relax to `1.2.3`, so semver alone calls them equal and `max` is free to hand back the older one.
        assertTrue(Version.parse("1.2.3.5") > Version.parse("1.2.3.4"))
        assertTrue(Version.parse("1.2.3.10") > Version.parse("1.2.3.9"))
        val releases = listOf(Version.parse("1.2.3.4"), Version.parse("1.2.3.5"), Version.parse("1.2.3.2"))
        assertEquals("1.2.3.5", releases.max().raw)
    }

    @Test
    fun anInferredNameIsAlwaysUsableAsADirectory() {
        val odd = Manifest.inferred(PackageRef.github("owner/.."))
        odd.validate()
    }

    @Test
    fun stripPrefixIsBounded() {
        base.copy(stripPrefix = 2uL).validate()
        assertFailsWith<KetchError> { base.copy(stripPrefix = ULong.MAX_VALUE).validate() }
    }

    @Test
    fun tagMatchingIgnoresVPrefix() {
        assertTrue(Version.parse("v14.1.0").matchesRequest("14.1.0"))
        assertTrue(Version.parse("14.1.0").matchesRequest("v14.1.0"))
        assertTrue(!Version.parse("14.1.0").matchesRequest("14.1.1"))
    }

    @Test
    fun globPreferredPicksTheStemNamedMatchOverDirectoryOrder() {
        val hook = Path("payload/rtok-hook.exe")
        val main = Path("payload/rtok.exe")
        val matched = listOf(hook, main)

        fun pick(
            m: List<Path>,
            n: String?,
        ) = globPreferred(Path("payload"), "rtok*", m, n)
        // NTFS lists rtok-hook.exe first; the stem preference must win anyway.
        assertEquals(main, pick(matched, "rtok"))
        // Case-insensitive like globMatch; a name carrying its own suffix still stems.
        assertEquals(main, pick(matched, "RTOK.EXE"))
        // One match is not ambiguous, whatever it is called; nothing matched is null.
        assertEquals(hook, pick(listOf(hook), null))
        assertNull(pick(emptyList(), "rtok"))
    }

    @Test
    fun globPreferredRefusesSeveralMatchesNoneNamedLikeTheLink() {
        val hook = Path("payload/bin/rtok-hook.exe")
        val main = Path("payload/bin/rtok.exe")
        // Both directory orders must give the same message.
        for (matched in listOf(listOf(hook, main), listOf(main, hook))) {
            for (name in listOf(null, "other")) {
                val err = assertFailsWith<KetchError> { globPreferred(Path("payload"), "bin/rtok*", matched, name) }.message
                assertTrue("`bin/rtok*` matches 2 files" in err, err)
                assertTrue("  bin/rtok-hook.exe\n  bin/rtok.exe\n" in err, "candidates sorted and root-relative: $err")
                assertTrue("set the entry's `name`" in err, err)
            }
        }
    }

    @Test
    fun globMatchesAssetNames() {
        assertTrue(globMatch("*-aarch64-apple-darwin.tar.gz", "rg-14-aarch64-apple-darwin.tar.gz"))
        assertTrue(globMatch("*.zip", "Tool-Universal.ZIP"))
        assertTrue(!globMatch("*.zip", "tool.tar.gz"))
        assertTrue(globMatch("rg?.tar.gz", "rg1.tar.gz"))
        // Pathological pattern must still terminate promptly.
        assertTrue(!globMatch("*a*a*a*a*b", "a".repeat(64)))
    }

    @Test
    fun hooksParseFromTheirTableAndABlankOneIsRefused() {
        val manifest = parse<Manifest>("name = \"tool\"\nsource = \"o/r\"\n[hooks]\nafter_install = \"./setup\"\n", "ketch.toml")
        assertEquals("./setup", manifest.hooks.afterInstall)
        assertNull(manifest.hooks.beforeInstall)
        manifest.validate()
        // Round-trips without writing the five unset keys.
        val written = render(manifest, "ketch.toml")
        assertTrue("after_install" in written, written)
        assertTrue("before_install" !in written, written)

        val blank = parse<Manifest>("name = \"tool\"\nsource = \"o/r\"\n[hooks]\nbefore_update = \" \"\n", "ketch.toml")
        val err = assertFailsWith<KetchError> { blank.validate() }.message
        assertTrue("hooks.before_update" in err, err)

        assertFailsWith<KetchError>("a misspelt hook key must not be ignored") {
            parse<Manifest>("name = \"tool\"\nsource = \"o/r\"\n[hooks]\nafter_instal = \"x\"\n", "ketch.toml")
        }
    }

    private fun releaseTagged(
        tag: String,
        prerelease: Boolean,
    ) = Release(Version.parse(tag), tag, prerelease = prerelease)

    @Test
    fun aStableReleaseInstalledWithoutPreIsOnTheStableChannel() {
        assertEquals(Channel.Stable, Channel.of(releaseTagged("v1.2.0", false), false, false))
    }

    @Test
    fun optingIntoPrereleasesKeepsThemEvenWhenTheNewestIsStable() {
        assertEquals(Channel.Pre, Channel.of(releaseTagged("v1.2.0", false), true, false))
    }

    @Test
    fun aPrereleaseByFlagOrByVersionPutsThePackageOnPre() {
        assertEquals(Channel.Pre, Channel.of(releaseTagged("v1.2.0", true), false, false))
        assertEquals(Channel.Pre, Channel.of(releaseTagged("v2.0.0-rc.1", false), false, false))
    }

    @Test
    fun optingIntoDraftsPutsThePackageOnDraftWhateverItLandedOn() {
        assertEquals(Channel.Draft, Channel.of(releaseTagged("v1.2.0", false), false, true))
        val draft = releaseTagged("v2.0.0", false).copy(draft = true)
        assertEquals(Channel.Draft, Channel.of(draft, true, false))
        assertEquals("draft", Channel.Draft.asStr)
    }
}
