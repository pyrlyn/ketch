package ketch.core.model

import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shapes Rust's serde gives the hand-written serializers: externally tagged and untagged enums. */
class ModelJsonTest {
    private val json = Json { encodeDefaults = true }

    private inline fun <reified T> roundTrip(
        value: T,
        text: String,
    ) {
        assertEquals(text, json.encodeToString(value))
        assertEquals(value, json.decodeFromString<T>(text))
    }

    @Test
    fun trustResultsAreExternallyTagged() {
        roundTrip<TrustResult>(TrustResult.Trusted("Developer ID"), """{"trusted":{"authority":"Developer ID"}}""")
        roundTrip<TrustResult>(TrustResult.Weak("ad hoc"), """{"weak":{"detail":"ad hoc"}}""")
        roundTrip<TrustResult>(TrustResult.NotApplicable, "\"not_applicable\"")
    }

    @Test
    fun manifestOriginsCarryTheirPath() {
        roundTrip<ManifestOrigin>(ManifestOrigin.User(Path("/m/rg.toml")), """{"user":"/m/rg.toml"}""")
        roundTrip<ManifestOrigin>(ManifestOrigin.Registry(Path("/r/rg")), """{"registry":"/r/rg"}""")
        roundTrip<ManifestOrigin>(ManifestOrigin.Builtin, "\"builtin\"")
        roundTrip<ManifestOrigin>(ManifestOrigin.Inferred, "\"inferred\"")
    }

    @Test
    fun extraPathsAreAStringOrATable() {
        roundTrip<ExtraPath>(ExtraPath.Plain("doc/rg.1"), "\"doc/rg.1\"")
        roundTrip<ExtraPath>(
            ExtraPath.Spec(ExtraPathSpec("misc/x", ExtraKind.Completion, shell = CompletionShell.Zsh)),
            """{"path":"misc/x","kind":"completion","shell":"zsh"}""",
        )
    }

    @Test
    fun aBinaryLinkLeavesItsRoleOut() {
        roundTrip(LinkRecord(Path("/b/rg"), Path("/s/rg"), LinkKind.Symlink), """{"link":"/b/rg","target":"/s/rg","kind":"symlink"}""")
        roundTrip(
            LinkRecord(Path("/m/rg.1"), Path("/s/rg.1"), LinkKind.Symlink, LinkRole.Man),
            """{"link":"/m/rg.1","target":"/s/rg.1","kind":"symlink","role":"man"}""",
        )
    }

    @Test
    fun assetHeadersAreWrittenInKeyOrder() {
        val asset = ReleaseAsset("a", "u", headers = mapOf("b" to "2", "a" to "1"))
        assertEquals(
            """{"name":"a","url":"u","size":0,"content_type":null,"digest":null,"headers":{"a":"1","b":"2"}}""",
            json.encodeToString(asset),
        )
    }
}
