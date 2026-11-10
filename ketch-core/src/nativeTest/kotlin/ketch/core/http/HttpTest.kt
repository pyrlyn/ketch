package ketch.core.http

import fs.joinPath
import http.HttpBody
import http.HttpClient
import http.HttpHead
import http.HttpRequest
import ketch.core.cancel.Cancel
import ketch.core.error.KetchError
import ketch.core.report.ProgressSink
import ketch.core.report.Report
import ketch.core.report.SilentProgress
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import loopback.LoopbackServer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTest {
    /** A body handed out in one piece, as a socket might. */
    private fun bodyOf(text: String) =
        object : HttpBody {
            var done = false

            override suspend fun read(buffer: ByteArray): Int {
                if (done) return -1
                done = true
                val bytes = text.encodeToByteArray()
                bytes.copyInto(buffer)
                return bytes.size
            }
        }

    private fun download(
        http: Http,
        url: String,
        dest: Path,
        progress: ProgressSink = SilentProgress,
        cancel: Cancel = Cancel(),
    ) = http.download(url, dest, emptyMap(), false, progress, cancel)

    @Test
    fun readsGithubErrorMessages() {
        val body = """{"message":"API rate limit exceeded","documentation_url":"https://x"}"""
        assertEquals("API rate limit exceeded", extractMessage(body))
    }

    @Test
    fun fallsBackToBodySnippet() {
        assertEquals("bad gateway", extractMessage("bad gateway"))
        assertNull(extractMessage("   "))
    }

    @Test
    fun aBodyAtTheCapIsAccepted() {
        assertEquals("abcd", runBlocking { readLimited(bodyOf("abcd"), 4, "https://example") })
    }

    @Test
    fun aBodyPastTheCapIsRefusedRatherThanTruncated() {
        val err = assertFailsWith<KetchError> { runBlocking { readLimited(bodyOf("abcde"), 4, "https://example") } }
        assertTrue("https://example" in err.message, err.message)
        assertTrue("truncated" in err.message, err.message)
    }

    @Test
    fun theDefaultDownloadCapIsFourGib() {
        assertEquals(4L * 1024 * 1024 * 1024, DEFAULT_MAX_DOWNLOAD)
        assertEquals(DEFAULT_MAX_DOWNLOAD, Http(null, Report.silent()).maxDownload)
    }

    /** No Content-Length, so only the count while streaming can stop it. */
    @Test
    fun aBodyStreamingPastAConfiguredCapIsRefusedAndLeavesNoFile(): Unit =
        withTempDir { dir ->
            val reply = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".encodeToByteArray() + ByteArray(1024 * 1024) { 7 }
            LoopbackServer { reply }.use { server ->
                val dest = joinPath(dir, "big.bin")
                val http = Http(null, Report.silent()).withMaxDownload(64 * 1024)
                val err = assertFailsWith<KetchError> { download(http, "${server.url}/big.bin", dest) }
                assertTrue("refusing the download" in err.message, err.message)
                assertTrue("max_download_mib" in err.message, err.message)
                assertEquals(emptyList(), SystemFileSystem.list(dir), "no staging file")
            }
        }

    /** The head alone decides: a body read here would fail the test. */
    @Test
    fun aDownloadLargerThanTheCapIsRefusedBeforeTheBody(): Unit =
        withTempDir { dir ->
            val client =
                object : HttpClient {
                    override suspend fun post(req: HttpRequest) = error("no POST")

                    override suspend fun <T> stream(
                        req: HttpRequest,
                        handle: suspend (HttpHead, HttpBody) -> T,
                    ): T = handle(HttpHead(200, listOf("Content-Length" to "${DEFAULT_MAX_DOWNLOAD + 1}"))) { error("the body was read") }

                    override fun close() = Unit
                }
            val dest = joinPath(dir, "huge.bin")
            val err = assertFailsWith<KetchError> { download(Http(null, Report.silent(), client = client), "https://x/huge.bin", dest) }
            assertTrue("refusing the download" in err.message, err.message)
            assertEquals(emptyList(), SystemFileSystem.list(dir))
        }

    /** Cancelling in the middle of a stream stops at the next chunk, and the staging file goes with it. */
    @Test
    fun aCancelledDownloadLeavesNoFileBehind(): Unit =
        withTempDir { dir ->
            val reply = "HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n".encodeToByteArray() + ByteArray(1024 * 1024) { 7 }
            LoopbackServer { reply }.use { server ->
                val cancel = Cancel()
                val stopAfterFirstChunk =
                    object : ProgressSink {
                        override fun start(
                            total: Long?,
                            label: String,
                        ) = Unit

                        override fun advance(delta: Long) = cancel.cancel()

                        override fun finish(message: String) = Unit
                    }
                val dest = joinPath(dir, "c.bin")
                assertFailsWith<KetchError.Cancelled> {
                    download(Http(null, Report.silent()), "${server.url}/c.bin", dest, stopAfterFirstChunk, cancel)
                }
                assertEquals(emptyList(), SystemFileSystem.list(dir))
            }
        }

    /** The bytes land at the destination whole, with the hash of what was written. */
    @Test
    fun aDownloadIsHashedAsItStreams(): Unit =
        withTempDir { dir ->
            LoopbackServer { "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc".encodeToByteArray() }.use { server ->
                val dest = joinPath(dir, "sub/f")
                val hex = download(Http(null, Report.silent()), "${server.url}/f", dest)
                assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
                assertEquals(hex, sha256File(dest))
            }
        }

    /** A status error keeps GitHub's own explanation, and a 404 is an ordinary answer where one is asked for. */
    @Test
    fun anErrorStatusCarriesTheServersMessage(): Unit =
        LoopbackServer { req ->
            val status = if ("/missing" in req.decodeToString().lineSequence().first()) "404 Not Found" else "403 Forbidden"
            val body = """{"message":"API rate limit exceeded"}"""
            "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\n\r\n$body".encodeToByteArray()
        }.use { server ->
            val http = Http(null, Report.silent())
            val err = assertFailsWith<KetchError.Http> { http.getText("${server.url}/limited", authed = false) }
            assertEquals(403, err.status)
            assertEquals("API rate limit exceeded", err.detail)
            assertNull(http.getJsonOpt<Map<String, String>>("${server.url}/missing", authed = false))
        }

    @Test
    fun hashesFiles(): Unit =
        withTempDir { dir ->
            val path = joinPath(dir, "f")
            path.write("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256File(path))
        }

    /**
     * The Curl engine on Linux cancels the body channel when the caller stops mid-stream; that must
     * not hide the refusal already thrown (a size cap, or a user cancel).
     */
    @Test
    fun aTransportCancelOnCloseDoesNotHideTheDownloadRefusal(): Unit =
        withTempDir { dir ->
            val dest = joinPath(dir, "big.bin")
            val err =
                assertFailsWith<KetchError> {
                    download(Http(null, Report.silent(), client = cancellingOnClose(streamingBody())).withMaxDownload(64 * 1024), "https://x/big.bin", dest)
                }
            assertTrue("refusing the download" in err.message, err.message)
            assertTrue("max_download_mib" in err.message, err.message)
            assertEquals(emptyList(), SystemFileSystem.list(dir))
        }

    @Test
    fun aTransportCancelOnCloseDoesNotHideAUserCancel(): Unit =
        withTempDir { dir ->
            val cancel = Cancel()
            val stopAfterFirstChunk =
                object : ProgressSink {
                    override fun start(
                        total: Long?,
                        label: String,
                    ) = Unit

                    override fun advance(delta: Long) = cancel.cancel()

                    override fun finish(message: String) = Unit
                }
            val dest = joinPath(dir, "c.bin")
            assertFailsWith<KetchError.Cancelled> {
                download(Http(null, Report.silent(), client = cancellingOnClose(streamingBody())), "https://x/c.bin", dest, stopAfterFirstChunk, cancel)
            }
            assertEquals(emptyList(), SystemFileSystem.list(dir))
        }
}

/** A body that yields more than the 64 KiB cap used in the close-on-cancel tests, in more than one chunk. */
private fun streamingBody(): HttpClient =
    object : HttpClient {
        override suspend fun post(req: HttpRequest) = error("no POST")

        override suspend fun <T> stream(
            req: HttpRequest,
            handle: suspend (HttpHead, HttpBody) -> T,
        ): T {
            val body =
                object : HttpBody {
                    var left = 256 * 1024

                    override suspend fun read(buffer: ByteArray): Int {
                        if (left <= 0) return -1
                        val n = minOf(buffer.size, left, 32 * 1024)
                        left -= n
                        return n
                    }
                }
            return handle(HttpHead(200, emptyList()), body)
        }

        override fun close() = Unit
    }

/**
 * Drops the handler's exception and throws [CancellationException], as the Curl engine does on Linux
 * when the response body is closed mid-stream.
 */
private fun cancellingOnClose(inner: HttpClient): HttpClient =
    object : HttpClient {
        override suspend fun post(req: HttpRequest) = inner.post(req)

        override suspend fun <T> stream(
            req: HttpRequest,
            handle: suspend (HttpHead, HttpBody) -> T,
        ): T =
            try {
                inner.stream(req, handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: KetchError) {
                throw CancellationException("curl body closed", e)
            }

        override fun close() = inner.close()
    }
