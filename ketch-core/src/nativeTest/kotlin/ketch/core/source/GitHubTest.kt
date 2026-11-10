package ketch.core.source

import ketch.core.error.KetchError
import ketch.core.http.API_JSON
import ketch.core.http.Http
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.Version
import ketch.core.model.VersionSpec
import ketch.core.report.Report
import ketch.core.testing.withFreshHttp
import loopback.LoopbackServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubTest {
    private fun source(api: String = DEFAULT_API) = GitHubSource(Http(null, Report.silent()), api)

    private fun ghRelease(json: String) = API_JSON.decodeFromString<GhRelease>(json)

    @Test
    fun parsesSha256sumFilesInTheirUsualShapes() {
        val body =
            """
            # generated
            9f2b1e0000000000000000000000000000000000000000000000000000000abc  rg-14.tar.gz
            5c3d000000000000000000000000000000000000000000000000000000000def *./dist/rg-14.zip
            not-a-hash                                                          junk.txt
            """.trimIndent()
        val map = parseChecksumFile(body)
        assertEquals(2, map.size)
        assertEquals("9f2b1e0000000000000000000000000000000000000000000000000000000abc", map["rg-14.tar.gz"])
        // Directory prefixes and the binary-mode star are both stripped.
        assertTrue("rg-14.zip" in map)
    }

    @Test
    fun anAmbiguousBasenameIsDroppedRatherThanOverwritten() {
        val map = parseChecksumFile("${"a".repeat(64)}  dir/tool.tar.gz\n${"b".repeat(64)}  other/tool.tar.gz\n")
        assertFalse("tool.tar.gz" in map, map.toString())
    }

    @Test
    fun aSidecarPrefersTheNamedAssetOverTheAlphabeticallyFirstLine() {
        val wanted = "c".repeat(64)
        assertEquals(wanted, sidecarHex("${"a".repeat(64)}  other.bin\n$wanted  tool.tar.gz\n", "tool.tar.gz"))
    }

    @Test
    fun readsTheApiDigestField() {
        assertEquals("a".repeat(64), parseDigest("sha256:${"a".repeat(64)}"))
        assertNull(parseDigest("md5:abc"))
        assertNull(parseDigest("sha256:tooshort"))
    }

    @Test
    fun recognisesAggregateChecksumAssets() {
        assertTrue(isAggregateChecksumFile("SHA256SUMS"))
        assertTrue(isAggregateChecksumFile("tool_1.0_checksums.txt"))
        assertFalse(isAggregateChecksumFile("rg-14.tar.gz"))
    }

    @Test
    fun namesTheAssetASidecarBelongsToWhateverCaseItIsWrittenIn() {
        assertEquals("rg-14.tar.gz", sidecarTarget("rg-14.tar.gz.sha256"))
        assertEquals("Tool.zip", sidecarTarget("Tool.zip.SHA256"))
        assertEquals("Tool.zip", sidecarTarget("Tool.zip.Sha256"))
        // Not a sidecar: no extension to strip, and a checksum list is the other branch.
        assertNull(sidecarTarget("SHA256SUMS"))
        assertNull(sidecarTarget("sha256"))
    }

    @Test
    fun derivesABrowsableUrlFromTheApiBase() {
        assertEquals("https://github.com/BurntSushi/ripgrep", source().webUrl("BurntSushi/ripgrep"))
    }

    @Test
    fun validatesRepositoryIdsBeforeBuildingApiUrls() {
        assertFailsWith<KetchError> { source().repoUrl("https://attacker.invalid/x", "") }
    }

    @Test
    fun aChecksumFetchFailureIsNotReportedAsAMissingFile() {
        val release =
            Release(
                Version.parse("1.0.0"),
                "v1.0.0",
                assets =
                    listOf(
                        ReleaseAsset("tool.tar.gz", "https://example.invalid/tool.tar.gz"),
                        ReleaseAsset("tool.tar.gz.sha256", "http://127.0.0.1:1/unreachable"),
                    ),
            )
        val err = assertFailsWith<KetchError> { source().checksums("owner/repo", release, "tool.tar.gz") }.message
        assertTrue("could not fetch checksum file" in err, "unexpected error: $err")
        assertFalse("no published checksum" in err, "fetch failure looked like a missing checksum: $err")
        assertFalse("does not exist" in err, "fetch failure looked like a missing file: $err")
    }

    @Test
    fun aDraftDownloadsFromItsApiUrlAndOnlyFromTheApiHost() {
        val draft =
            ghRelease(
                """{"tag_name":"v2.0.0","draft":true,"assets":[
                {"name":"ok.tar.gz","browser_download_url":"https://github.com/o/r/releases/download/untagged-1/ok.tar.gz",
                 "url":"https://api.github.com/repos/o/r/releases/assets/1"},
                {"name":"elsewhere.tar.gz","browser_download_url":"https://github.com/x",
                 "url":"https://api.github.com.attacker.invalid/repos/o/r/releases/assets/2"},
                {"name":"no-api-url.tar.gz","browser_download_url":"https://github.com/y"}]}""",
            )
        val release = releaseFrom(draft, "https://api.github.com")
        assertTrue(release.draft)
        assertEquals(listOf("ok.tar.gz" to "https://api.github.com/repos/o/r/releases/assets/1"), release.assets.map { it.name to it.url })
    }

    @Test
    fun aPublishedReleaseKeepsItsPublicDownloadUrl() {
        val published =
            ghRelease(
                """{"tag_name":"v1.0.0","assets":[{"name":"ok.tar.gz",
                "browser_download_url":"https://github.com/o/r/releases/download/v1.0.0/ok.tar.gz",
                "url":"https://api.github.com/repos/o/r/releases/assets/1"}]}""",
            )
        assertEquals("https://github.com/o/r/releases/download/v1.0.0/ok.tar.gz", releaseFrom(published, DEFAULT_API).assets[0].url)
    }

    @Test
    fun draftsAreListedOnlyWhenAskedAndOnlyWithATag() {
        val raw =
            listOf(
                ghRelease("""{"tag_name":"v2.0.0","draft":true}"""),
                ghRelease("""{"tag_name":" ","draft":true}"""),
                ghRelease("""{"tag_name":"v1.0.0"}"""),
            )

        fun tags(opts: ListOpts) = publishedReleases(raw, opts, DEFAULT_API).map { it.tag }
        assertEquals(listOf("v1.0.0"), tags(ListOpts()))
        assertEquals(listOf("v2.0.0", "v1.0.0"), tags(ListOpts(includeDraft = true)))
    }

    @Test
    fun askingForDraftsWithoutATokenNamesTheTokenInsteadOfFindingNothing() {
        // Unreachable: the refusal has to come before any request.
        val source = source("http://127.0.0.1:1")
        val opts = ListOpts(includeDraft = true)
        for (err in listOf(
            assertFailsWith<KetchError> { source.listReleases("o/r", opts) },
            assertFailsWith<KetchError> { source.resolve("o/r", VersionSpec.Latest, opts) },
        )) {
            assertTrue("GITHUB_TOKEN" in err.message, err.message)
            assertTrue("push access" in err.message, err.message)
        }
    }

    @Test
    fun encodesReleaseTagsAsOnePathSegment() {
        assertEquals("release%2Fv1%20beta%3F", urlencodePathSegment("release/v1 beta?"))
    }

    @Test
    fun anExactTagNotOnTheFirstListPageIsStillResolved() {
        LoopbackServer(::serveReleaseList).use { server ->
            withFreshHttp { http ->
                val got = GitHubSource(http, server.url).resolve("acme/busy", VersionSpec.Exact("old-0.1.0"), ListOpts())
                assertEquals("old-0.1.0", got.tag)
            }
        }
    }

    /** GitHub for a busy repository: the tag endpoints miss, page one is full of newer releases, page two holds the old one. */
    private fun serveReleaseList(request: ByteArray): ByteArray {
        val path =
            request
                .decodeToString()
                .lineSequence()
                .first()
                .split(' ')
                .getOrElse(1) { "" }
        val pathOnly = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&')

        fun param(key: String) = query.firstNotNullOfOrNull { it.removePrefix("$key=").takeIf { v -> v.length < it.length } }
        val (status, body) =
            when {
                "/releases/tags/" in pathOnly -> {
                    "404 Not Found" to """{"message":"Not Found"}"""
                }

                pathOnly.endsWith("/releases") -> {
                    val perPage = param("per_page")?.toIntOrNull() ?: 30
                    "200 OK" to
                        when (param("page") ?: "1") {
                            // A full first page, so a one-shot list would miss page 2.
                            "1" -> (0 until perPage).joinToString(",", "[", "]") { """{"tag_name":"v9.9.$it","assets":[]}""" }

                            "2" -> """[{"tag_name":"old-0.1.0","assets":[]}]"""

                            else -> "[]"
                        }
                }

                else -> {
                    "404 Not Found" to """{"message":"Not Found"}"""
                }
            }
        return "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            .encodeToByteArray()
    }
}
