package ketch.core.manifest

import ketch.core.error.KetchError
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.PackageRef
import ketch.core.model.PackageSpec
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolverTest {
    private fun builtin() = parseRegistry(BUILTIN_TOML, "builtin")

    private fun resolver() = Resolver(builtin(), emptyList(), emptyList())

    @Test
    fun builtinRegistryParsesAndIsReachableByAlias() {
        assertTrue(builtin().isNotEmpty())
        val (manifest, origin) = resolver().resolve(PackageSpec.parse("rg"))
        assertEquals("ripgrep", manifest.name)
        assertEquals(ManifestOrigin.Builtin, origin)
    }

    @Test
    fun aRegistryFileWithAStrayTopLevelKeyIsRefused() {
        val text = "oops = true\n\n[[package]]\nname = \"thing\"\nsource = \"github:o/thing\"\n"
        val msg = assertFailsWith<KetchError.Parse> { parseRegistry(text, "test") }.message
        assertTrue("unknown key" in msg && "oops" in msg, msg)
    }

    @Test
    fun aSingleManifestThatMentionsTheArrayMarkerIsStillAManifest() {
        // Sniffing the source text for `[[package]]` parsed this as a registry of zero packages and dropped the file.
        val text = "name = \"thing\"\nsource = \"github:o/thing\"\nnotes = \"declare it under [[package]] to ship it\"\n"
        val found = parseRegistry(text, "test")
        assertEquals(1, found.size)
        assertEquals("thing", found[0].name)
    }

    @Test
    fun aReferenceStillPicksUpTheCuratedManifest() {
        val (manifest, origin) = resolver().resolve(PackageSpec.parse("BurntSushi/ripgrep@14.1.0"))
        assertEquals(ManifestOrigin.Builtin, origin)
        assertEquals("rg", manifest.bin.firstOrNull()?.name)
    }

    @Test
    fun aReferenceMatchesTheCuratedEntryWhateverItsCase() {
        // GitHub is case-insensitive, so this is the same repository, and the curated manifest says which binary to link.
        for (reference in listOf("burntsushi/ripgrep", "BurntSushi/RipGrep")) {
            val (manifest, origin) = resolver().resolve(PackageSpec.parse(reference))
            assertEquals(ManifestOrigin.Builtin, origin, reference)
            assertEquals("rg", manifest.bin.firstOrNull()?.name, reference)
        }
    }

    @Test
    fun anUncuratedReferenceFallsThroughToInference() {
        val (manifest, origin) = resolver().resolve(PackageSpec.parse("someone/whatever-tool"))
        assertEquals(ManifestOrigin.Inferred, origin)
        assertEquals("whatever-tool", manifest.name)
        assertEquals("someone/whatever-tool", manifest.source.id)
    }

    @Test
    fun anUnknownBareNameIsAnErrorNotAGuess() {
        assertFailsWith<KetchError> { resolver().resolve(PackageSpec.parse("definitely-not-a-package")) }
    }

    @Test
    fun theRegistryShadowsBuiltinsAndListsEachNameOnce() {
        val entry = Manifest.inferred(PackageRef.github("registry/ripgrep")).copy(provides = listOf("rg"))
        val resolver = Resolver(builtin(), listOf(entry to Path("/tmp/registry/ripgrep/ketch.toml")), emptyList())

        val (manifest, origin) = resolver.resolve(PackageSpec.parse("rg"))
        assertEquals("registry/ripgrep", manifest.source.id)
        assertIs<ManifestOrigin.Registry>(origin)

        // The built-in `ripgrep` is the same package by another route, so it must not show up as a second result.
        val hits = resolver.search("ripgrep")
        assertEquals(1, hits.size)
        assertEquals("registry/ripgrep", hits[0].source.id)
    }

    @Test
    fun userManifestsShadowBuiltins() {
        val mine = Manifest.inferred(PackageRef.github("me/my-ripgrep")).copy(provides = listOf("rg"))
        val resolver = Resolver(builtin(), emptyList(), listOf(mine to Path("/tmp/my-ripgrep.toml")))
        val (manifest, origin) = resolver.resolve(PackageSpec.parse("rg"))
        assertEquals("me/my-ripgrep", manifest.source.id)
        assertIs<ManifestOrigin.User>(origin)
    }
}
