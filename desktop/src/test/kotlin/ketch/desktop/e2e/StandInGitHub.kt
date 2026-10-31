// GitHub as `ketch` sees it through KETCH_GITHUB_API, answered from desktop/e2e/data: the registry as a tarball, each
// repository's details and releases, and a release's assets, each a small program built on the first request. The JDK's
// own server is enough, and a release can be held back so a test can publish it while the app runs.
package ketch.desktop.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val REGISTRY = "pyrlyn/ketch-registry"
private const val OK = 200
private const val NOT_FOUND = 404
private const val NO_BODY = -1L

/** A release's asset is named for the host, as ketch would pick it. */
private val hostTriple =
    System.getProperty("os.name").let { os ->
        val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "aarch64" else "x86_64"
        when {
            os.startsWith("Mac") -> "$arch-apple-darwin"
            os.startsWith("Windows") -> "$arch-pc-windows-msvc"
            else -> "$arch-unknown-linux-musl"
        }
    }

class StandInGitHub(
    private val data: File,
    private val scratch: File,
) : AutoCloseable {
    private val repos: Map<String, JsonObject> =
        Json
            .parseToJsonElement(data.resolve("github.json").readText())
            .jsonObject
            .mapValues { it.value.jsonObject }
    private val held = ConcurrentHashMap.newKeySet<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val assets = ConcurrentHashMap<String, ByteArray>()

    /** Every request nothing here answers, which ketch reads as "no such thing". */
    val unanswered = CopyOnWriteArrayList<String>()

    /** The API base for KETCH_GITHUB_API. */
    val api get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/", ::answer)
        server.start()
    }

    /** Keeps [tag] of [repo] out of every answer until [publish]. */
    fun hold(
        repo: String,
        tag: String,
    ) {
        held += "$repo@$tag"
    }

    fun publish(
        repo: String,
        tag: String,
    ) {
        held -= "$repo@$tag"
    }

    override fun close() = server.stop(0)

    private fun answer(exchange: HttpExchange) =
        exchange.use {
            val path = URLDecoder.decode(it.requestURI.rawPath, Charsets.UTF_8).trim('/')
            val query = it.requestURI.rawQuery.orEmpty()
            val body = route(path, query)
            if (body == null) {
                unanswered += "$path?$query"
                it.sendResponseHeaders(NOT_FOUND, NO_BODY)
            } else {
                it.sendResponseHeaders(OK, body.size.toLong())
                it.responseBody.write(body)
            }
        }

    private fun route(
        path: String,
        query: String,
    ): ByteArray? {
        val parts = path.split('/')
        return when {
            path == "repos/$REGISTRY/tarball" -> registry()
            path == "search/repositories" -> json(search(query))
            parts[0] == "download" && parts.size == 5 -> asset("${parts[1]}/${parts[2]}", parts[3], parts[4])
            parts[0] != "repos" || parts.size < 3 -> null
            else -> repository("${parts[1]}/${parts[2]}", parts.drop(3), query)
        }
    }

    private fun repository(
        id: String,
        rest: List<String>,
        query: String,
    ): ByteArray? {
        val repo = repos[id] ?: return null
        val releases = releases(id)
        return when {
            rest.isEmpty() -> json(details(id, repo))
            rest == listOf("releases") -> json(JsonArray(if ("page=1" in query.split('&') || "page=" !in query) releases else emptyList()))
            rest == listOf("releases", "latest") -> releases.firstOrNull()?.let(::json)
            rest.size == 3 && rest[1] == "tags" -> releases.firstOrNull { tag(it) == rest[2] }?.let(::json)
            else -> null
        }
    }

    private fun details(
        id: String,
        repo: JsonObject,
    ) = JsonObject(repo - "releases" - "tool" + ("full_name" to JsonPrimitive(id)))

    private fun search(query: String): JsonObject {
        // GitHub reads `q` as words; the data's words are few enough for a plain match.
        val words =
            query
                .substringAfter("q=")
                .substringBefore('&')
                .let { URLDecoder.decode(it, Charsets.UTF_8) }
                .lowercase()
        val found = repos.filter { (id, repo) -> words.split(' ').any { it in id.lowercase() || it in repo["description"].toString().lowercase() } }
        return buildJsonObject { put("items", JsonArray(found.map { (id, repo) -> details(id, repo) })) }
    }

    private fun tag(release: JsonObject) = release.getValue("tag_name").jsonPrimitive.content

    private fun tool(id: String) =
        repos
            .getValue(id)
            .getValue("tool")
            .jsonPrimitive.content

    private fun version(tag: String) = tag.removePrefix("v")

    private fun releases(id: String): List<JsonObject> =
        repos
            .getValue(id)
            .getValue("releases")
            .jsonArray
            .map { it.jsonObject }
            .filter { "$id@${tag(it)}" !in held }
            .map { release ->
                val name = "${tool(id)}-${version(tag(release))}-$hostTriple.tar.gz"
                val bytes = assetBytes(id, tag(release), name)
                val hex = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
                val assetJson =
                    buildJsonObject {
                        put("name", name)
                        put("browser_download_url", "$api/download/$id/${tag(release)}/$name")
                        put("size", bytes.size)
                        put("digest", "sha256:$hex")
                    }
                JsonObject(release + ("assets" to buildJsonArray { add(assetJson) }))
            }

    private fun asset(
        id: String,
        tag: String,
        name: String,
    ) = if (id in repos) assetBytes(id, tag, name) else null

    private fun assetBytes(
        id: String,
        tag: String,
        name: String,
    ): ByteArray =
        assets.computeIfAbsent(name) {
            val dir = scratch.resolve("assets/${name.removeSuffix(".tar.gz")}").apply { mkdirs() }
            program(dir, tool(id), version(tag))
            tarGz(dir, scratch.resolve("assets/$name"))
        }

    /** A program that prints its version, so a test can prove the one on disk is the one installed. */
    private fun program(
        dir: File,
        tool: String,
        version: String,
    ) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            dir.resolve("$tool.cmd").writeText("@echo off\r\necho $tool $version\r\n")
        } else {
            dir.resolve(tool).apply {
                writeText("#!/bin/sh\necho '$tool $version'\n")
                setExecutable(true, false)
            }
        }
    }

    /** GitHub wraps a repository's tarball in one `owner-repo-<sha>` directory. */
    private fun registry(): ByteArray {
        val dir = scratch.resolve("registry").apply { deleteRecursively() }
        data.resolve("registry").copyRecursively(dir.resolve("pyrlyn-ketch-registry-e2e"))
        return tarGz(dir, scratch.resolve("registry.tar.gz"))
    }

    // tar is on all three systems, and the test JDK has no writer for it.
    private fun tarGz(
        dir: File,
        out: File,
    ): ByteArray {
        val process =
            ProcessBuilder(listOf("tar", "-czf", out.path, "-C", dir.path) + dir.list().orEmpty())
                .apply { environment()["COPYFILE_DISABLE"] = "1" }
                .inheritIO()
                .start()
        check(process.waitFor() == 0) { "tar failed for $dir" }
        return out.readBytes()
    }

    private fun json(value: JsonElement) = value.toString().encodeToByteArray()
}
