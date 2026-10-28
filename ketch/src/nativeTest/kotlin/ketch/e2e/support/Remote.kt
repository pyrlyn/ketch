// One local HTTP server that plays every remote a test needs (Rust tests/support/remote.rs): a stand-in GitHub API
// under `/gh` (point `KETCH_GITHUB_API` at [Remote.githubApi]), package catalogues and asset downloads, each answered
// from a table the test can change between runs. It keeps every request it was sent, so a test can prove where a
// header (a token above all) did and did not go.
@file:OptIn(ExperimentalAtomicApi::class)

package ketch.e2e.support

import loopback.LoopbackServer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/** One request as the server saw it. */
data class Seen(
    /** Path and query, as sent. */
    val target: String,
    val authorization: String?,
    val accept: String?,
)

/** Answers each request target from its table; anything else is a 404, which every client reads as "no such thing". */
class Remote : AutoCloseable {
    // The server answers on its own thread; immutable snapshots swapped atomically keep the two sides apart.
    private val routes = AtomicReference(mapOf<String, ByteArray>())
    private val seenLog = AtomicReference(listOf<Seen>())
    private val server = LoopbackServer(::answer)

    val base get() = server.url

    /** The API base for `KETCH_GITHUB_API`. */
    val githubApi get() = "$base/gh"

    fun serve(
        target: String,
        body: ByteArray,
    ) = routes.update { it + (target to body) }

    fun serve(
        target: String,
        body: String,
    ) = serve(target, body.encodeToByteArray())

    /** Stop answering [target]: it is a 404 from now on. */
    fun forget(target: String) = routes.update { it - target }

    /** Every target served now, with its body. */
    fun routes(): Map<String, ByteArray> = routes.load()

    fun clear() = routes.store(emptyMap())

    /** Every request so far, oldest first. */
    fun seen(): List<Seen> = seenLog.load()

    override fun close() = server.close()

    private fun answer(request: ByteArray): ByteArray {
        val head = request.decodeToString().substringBefore("\r\n\r\n")
        val target =
            head
                .lineSequence()
                .first()
                .split(' ')
                .getOrNull(1) ?: "/"

        fun header(name: String) =
            head.lineSequence().drop(1).firstNotNullOfOrNull { line ->
                val key = line.substringBefore(':', "")
                if (key.trim().equals(name, ignoreCase = true)) line.substringAfter(':').trim() else null
            }
        seenLog.update { it + Seen(target, header("authorization"), header("accept")) }
        val body = routes.load()[target]
        val status = if (body != null) "200 OK" else "404 Not Found"
        val bytes = body ?: """{"message":"Not Found"}""".encodeToByteArray()
        return "HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + bytes
    }
}
