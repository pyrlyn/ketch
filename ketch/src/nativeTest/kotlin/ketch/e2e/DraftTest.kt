// Draft releases through the real binary and a stand-in GitHub API (Rust tests/draft.rs). Drafts are GitHub's alone
// (source plugins never see them), so the fixture plugin cannot play this part: a local server answers as the API
// does for a token with push access, and records every request, so the tests can see where the token went.
package ketch.e2e

import fs.bytesIfExists
import ketch.core.hash.Sha256
import ketch.e2e.support.Entry
import ketch.e2e.support.Remote
import ketch.e2e.support.Run
import ketch.e2e.support.Sandbox
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.ketchOverrides
import ketch.e2e.support.nativeName
import ketch.e2e.support.resolve
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val REPO = "acme/demo"
private const val TOKEN = "draft-test-token"

class DraftTest {
    private val sandbox = Sandbox()
    private val remote = Remote()

    @AfterTest
    fun cleanUp() {
        remote.close()
        sandbox.close()
    }

    private fun repo(push: Boolean) =
        remote.serve(
            "/gh/repos/$REPO",
            buildJsonObject {
                put("full_name", REPO)
                putJsonObject("permissions") { put("push", push) }
            }.toString(),
        )

    /**
     * One release in the API's shape, its asset served from both URLs a published release has; a draft's public URL
     * is left a 404, as it is on GitHub until the draft is published.
     */
    private fun release(
        version: String,
        id: Int,
        draft: Boolean,
    ): JsonObject {
        val file = nativeName("demo", version)
        val path = sandbox.fixture(file)
        tarGz(Entry.program("demo-$version/demo", "demo $version")).writeTo(path)
        val bytes = checkNotNull(bytesIfExists(path))
        remote.serve("/gh/repos/$REPO/releases/assets/$id", bytes)
        val public = "/dl/v$version/$file"
        if (draft) remote.forget(public) else remote.serve(public, bytes)
        return buildJsonObject {
            put("tag_name", "v$version")
            put("draft", draft)
            put("prerelease", false)
            put("published_at", if (draft) JsonNull else JsonPrimitive("2026-10-10T00:00:00Z"))
            put(
                "assets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", file)
                            put("browser_download_url", "${remote.base}$public")
                            put("url", "${remote.githubApi}/repos/$REPO/releases/assets/$id")
                            put("size", 1)
                            put("digest", "sha256:${Sha256.hex(bytes)}")
                        },
                    )
                },
            )
        }
    }

    /** What the release listing returns, newest first. The newest published one is also `latest`, as GitHub answers it. */
    private fun list(vararg releases: JsonObject) {
        // 30 is a resolve's page, 20 a listing's.
        for (perPage in listOf(30, 20)) remote.serve("/gh/repos/$REPO/releases?per_page=$perPage&page=1", JsonArray(releases.toList()).toString())
        val latest = releases.firstOrNull { it["draft"] == JsonPrimitive(false) }
        if (latest != null) remote.serve("/gh/repos/$REPO/releases/latest", latest.toString()) else remote.forget("/gh/repos/$REPO/releases/latest")
    }

    private fun run(
        token: Boolean,
        vararg args: String,
    ): Run {
        val env = mutableMapOf("KETCH_GITHUB_API" to remote.githubApi)
        if (token) env["KETCH_GITHUB_TOKEN"] = TOKEN
        return sandbox.ketchOverrides(args.toList(), env)
    }

    private fun ok(
        token: Boolean,
        vararg args: String,
    ) = run(token, *args).also { assertTrue(it.success, "`ketch ${args.joinToString(" ")}` failed\n$it") }

    private fun fails(
        token: Boolean,
        vararg args: String,
    ) = run(token, *args).also { assertFalse(it.success, "`ketch ${args.joinToString(" ")}` was expected to fail") }.stderr

    /** `ketch list --json`'s row for the package, asked afresh: the latest cache would otherwise answer from the last listing. */
    private fun listed(vararg args: String): JsonElement {
        runCatching { SystemFileSystem.delete(sandbox.root.resolve("cache/latest.json")) }
        val out = json(ok(true, "list", "--json", *args).stdout)
        return out["packages"].items.firstOrNull { it["name"].str == "demo" } ?: error("demo is listed: $out")
    }

    private fun tags(
        token: Boolean,
        vararg args: String,
    ) = (json(ok(token, *args).stdout) as? JsonArray).orEmpty().map { "${it["tag"].str ?: "?"} ${it["kind"].str ?: "?"}" }

    private fun info() = json(ok(false, "info", "demo", "--json").stdout)

    private fun outdated() = json(ok(true, "outdated", "--json").stdout)["outdated"]

    @Test
    fun askingForADraftWithoutATokenNamesTheToken() {
        repo(push = true)
        list(release("2.0.0", 2, draft = true))
        val err = fails(false, "install", REPO, "--draft", "-y")
        assertTrue("GITHUB_TOKEN" in err, err)
        assertTrue("push access" in err, err)
        assertFalse("\"demo\"" in sandbox.state())
    }

    @Test
    fun aTokenWithoutPushAccessIsRefusedForThatReason() {
        repo(push = false)
        list(release("2.0.0", 2, draft = true))
        val err = fails(true, "install", REPO, "--draft", "-y")
        assertTrue("no push access" in err, err)
    }

    @Test
    fun aDraftInstallsThroughTheApiAndItsPublicationIsAnUpgrade() {
        repo(push = true)
        val stable = release("1.0.0", 1, draft = false)
        list(release("2.0.0", 2, draft = true), stable)

        // Without --draft the draft is never chosen, token or not.
        ok(true, "install", REPO, "-y")
        assertEquals("v1.0.0", info()["installed"].str)

        val before = remote.seen().size
        ok(true, "install", REPO, "--draft", "-y")
        var info = info()
        assertEquals("v2.0.0", info["installed"].str)
        assertEquals("draft", info["channel"].str)
        assertTrue("\"draft\": true" in sandbox.state())

        val seen = remote.seen().drop(before)
        val asset = seen.firstOrNull { it.target == "/gh/repos/$REPO/releases/assets/2" } ?: error("the draft's asset came through the API: $seen")
        assertEquals("Bearer $TOKEN", asset.authorization)
        assertEquals("application/octet-stream", asset.accept)
        assertTrue(seen.all { it.target.startsWith("/gh/") }, "a draft install talks to the API host only: $seen")

        // Published under the same tag: the release the draft previewed.
        list(release("2.0.0", 2, draft = false), stable)
        val report = outdated()
        assertEquals("demo", report[0]["name"].str, "$report")
        ok(true, "upgrade", "-y")
        info = info()
        assertEquals("v2.0.0", info["installed"].str)
        assertEquals("draft", info["channel"].str, "the channel is kept")
        assertFalse("\"draft\": true" in sandbox.state())
        assertEquals(JsonArray(emptyList()), outdated())
    }

    @Test
    fun aDeletedDraftIsAWarningNotAnError() {
        repo(push = true)
        val stable = release("1.0.0", 1, draft = false)
        list(release("2.0.0", 2, draft = true), stable)
        ok(true, "install", REPO, "--draft", "-y")

        list(stable)
        val out = ok(true, "outdated", "--json")
        assertEquals(JsonArray(emptyList()), json(out.stdout)["outdated"], out.stdout)
        assertTrue("draft release v2.0.0" in out.stderr, out.stderr)
        assertTrue("is gone" in out.stderr, out.stderr)
    }

    @Test
    fun releasesListsDraftsOnlyWhenAskedAndAllowed() {
        repo(push = true)
        list(release("2.0.0", 2, draft = true), release("1.0.0", 1, draft = false))

        assertEquals(listOf("v1.0.0 stable"), tags(true, "releases", REPO, "--json"))
        var err = fails(false, "releases", REPO, "--draft")
        assertTrue("GITHUB_TOKEN" in err, err)
        assertEquals(listOf("v2.0.0 draft", "v1.0.0 stable"), tags(true, "releases", REPO, "--draft", "--json"))
        val out = ok(true, "info", REPO, "--releases", "--draft").stdout
        assertTrue("v2.0.0" in out, out)

        // A draft install asks for drafts by itself.
        ok(true, "install", REPO, "--draft", "-y")
        assertEquals(listOf("v2.0.0 draft", "v1.0.0 stable"), tags(true, "releases", "demo", "--json"))
        err = fails(false, "releases", "demo")
        assertTrue("GITHUB_TOKEN" in err, err)
    }

    @Test
    fun listAgreesWithOutdatedAboutDrafts() {
        repo(push = true)
        val stable = release("1.0.0", 1, draft = false)
        list(release("2.0.0", 2, draft = true), stable)
        ok(true, "install", REPO, "-y")

        var row = listed()
        assertEquals("v1.0.0", row["latest"].str, "$row")
        assertEquals(JsonPrimitive(false), row["update_available"], "$row")
        row = listed("--draft")
        assertEquals("v2.0.0", row["latest"].str, "$row")
        assertEquals(JsonPrimitive(true), row["update_available"], "$row")

        // On the draft channel, the draft's own publication is the update.
        ok(true, "install", REPO, "--draft", "-y")
        assertEquals(JsonPrimitive(false), listed()["update_available"])
        list(release("2.0.0", 2, draft = false), stable)
        row = listed()
        assertEquals(JsonPrimitive(true), row["update_available"], "$row")
        val report = outdated()
        assertEquals("demo", report[0]["name"].str, "$report")
    }
}
