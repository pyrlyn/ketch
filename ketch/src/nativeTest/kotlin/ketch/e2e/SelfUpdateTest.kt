// `ketch self upgrade --dry-run` must not claim an update when already current, and `--pre` must look past
// `/releases/latest` to the newest prerelease (Rust tests/self_update.rs).
package ketch.e2e

import fs.TempDir
import ketch.core.VERSION
import ketch.e2e.support.KETCH_EXE
import ketch.e2e.support.Run
import ketch.e2e.support.resolve
import ketch.e2e.support.runOnce
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loopback.LoopbackServer
import process.Command
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A release in the API's shape, with no assets: a dry run never downloads. */
private fun release(
    tag: String,
    prerelease: Boolean = false,
    body: String? = null,
) = buildJsonObject {
    put("tag_name", tag)
    put("prerelease", prerelease)
    put("draft", false)
    put("assets", JsonArray(emptyList()))
    body?.let { put("body", it) }
}

/** A listing as the ketch repository has it: a CLI prerelease newer than the stable `latest`, and the desktop app's releases beside it. */
private fun listingWithACliPrerelease() =
    buildJsonArray {
        add(release("desktop-v9999.0.0"))
        add(release("desktop-appcast", prerelease = true))
        add(release("v999.0.0-rc.1", prerelease = true))
        add(release("v0.0.1"))
    }

class SelfUpdateTest {
    private val temp = TempDir.create("ketch-self")
    private var server: LoopbackServer? = null

    @AfterTest
    fun cleanUp() {
        server?.close()
        temp.close()
    }

    /** [latest] answers `/releases/latest`; [listing] the `/releases?…` listing only `--pre` asks for. */
    private fun mock(
        latest: JsonObject,
        listing: JsonArray = JsonArray(emptyList()),
    ): String {
        val answer = { request: ByteArray ->
            val target =
                request
                    .decodeToString()
                    .substringBefore("\r\n")
                    .split(' ')
                    .getOrNull(1)
                    .orEmpty()
            val reply = (if ("/releases?" in target) listing.toString() else latest.toString()).encodeToByteArray()
            "HTTP/1.1 200 OK\r\nContent-Length: ${reply.size}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".encodeToByteArray() + reply
        }
        return LoopbackServer(answer).also { server = it }.url
    }

    private fun selfUpgrade(
        api: String,
        vararg args: String,
        verb: String = "upgrade",
    ): Run {
        val cmd = Command(KETCH_EXE, listOf("--root", temp.path.resolve("root").toString(), "self", verb) + args).env("NO_COLOR", "1").env("KETCH_GITHUB_API", api)
        listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy").forEach(cmd::envRemove)
        return cmd.runOnce().also { assertTrue(it.success, "self $verb failed: $it") }
    }

    @Test
    fun dryRunWhenAlreadyCurrentDoesNotClaimAnUpdate() {
        val out = selfUpgrade(mock(release("v$VERSION")), "--dry-run")
        assertTrue(out.stdout.isEmpty(), out.stdout)
        assertTrue("already current" in out.stderr, out.stderr)
        assertFalse("would upgrade" in out.stderr, out.stderr)
    }

    @Test
    fun selfUpdateAliasStillDryRuns() {
        val out = selfUpgrade(mock(release("v$VERSION")), "--dry-run", verb = "update")
        assertTrue("already current" in out.stderr, out.stderr)
    }

    @Test
    fun dryRunWhenANewerReleaseExistsClaimsAnUpdate() {
        val out = selfUpgrade(mock(release("v999.0.0")), "--dry-run")
        assertTrue(out.stdout.isEmpty(), out.stdout)
        assertTrue("would upgrade" in out.stderr, out.stderr)
        assertFalse("already current" in out.stderr, out.stderr)
    }

    @Test
    fun releaseNotesAreFilteredBeforeTheyReachStdout() {
        val out = selfUpgrade(mock(release("v999.0.0", body = "safe‮release notes")), "--dry-run")
        assertFalse('‮' in out.stdout, "a bidi override reached the terminal: ${out.stdout}")
        assertTrue("saferelease notes" in out.stdout, out.stdout)
    }

    @Test
    fun withoutPreANewerPrereleaseIsNotOffered() {
        val out = selfUpgrade(mock(release("v0.0.1"), listingWithACliPrerelease()), "--dry-run")
        assertTrue("already current" in out.stderr, out.stderr)
        assertFalse("999.0.0-rc.1" in out.stderr, out.stderr)
    }

    @Test
    fun preOffersTheNewestCliPrereleaseAndSkipsDesktopTags() {
        val out = selfUpgrade(mock(release("v0.0.1"), listingWithACliPrerelease()), "--dry-run", "--pre")
        assertTrue("would upgrade" in out.stderr, out.stderr)
        assertTrue("-> v999.0.0-rc.1" in out.stderr, out.stderr)
        assertFalse("desktop" in out.stderr, out.stderr)
    }
}
