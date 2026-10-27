package ketch.core.push

import ketch.core.error.KetchError
import ketch.core.http.Http
import ketch.core.http.Verb
import ketch.core.http.decodeApiJson
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.apiBase
import ketch.core.source.urlencode
import ketch.core.source.urlencodePathSegment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.posix.sleep
import kotlin.io.encoding.Base64

private const val UNPROCESSABLE = 422
private const val FORK_TRIES = 10
private const val FORK_WAIT_S = 3u

@Serializable
private class RepoJson(
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    val permissions: Permissions? = null,
)

@Serializable
private class Permissions(
    val push: Boolean = false,
)

@Serializable
private class RefJson(
    @SerialName("object") val target: RefObject,
)

@Serializable
private class RefObject(
    val sha: String = "",
)

@Serializable
private class ContentJson(
    val sha: String,
    val content: String? = null,
)

@Serializable
private class PullJson(
    @SerialName("html_url") val htmlUrl: String? = null,
    val base: PullBase? = null,
)

@Serializable
private class PullBase(
    val ref: String,
)

/**
 * Turn one contents-API item into a [RemoteFile], refusing the oversized.
 *
 * GitHub nulls `content` on files over 1 MB; defaulting that to an empty string would present the file as an update
 * whose diff deletes everything in it, so it is an error instead.
 */
internal fun fileText(
    path: String,
    content: String?,
    sha: String,
): RemoteFile =
    RemoteFile(
        sha,
        content ?: throw KetchError.Msg("$path is over the 1 MB the contents API returns, so ketch cannot read it; refusing to treat it as an empty file"),
    )

/** The text of a contents-API `content` field: base64, wrapped at 60 columns. null when it is not base64 UTF-8 text. */
private fun decodedContent(content: String?): String? {
    val bytes =
        try {
            content?.filterNot { it.isWhitespace() }?.let { Base64.decode(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
    return bytes?.runCatching { decodeToString(throwOnInvalidSequence = true) }?.getOrNull()
}

/** github.com, over its REST API, as the token's user. */
class GitHub internal constructor(
    private val http: Http,
) : Api {
    private val api = apiBase()

    private fun url(path: String) = "$api$path"

    /** A GET where 404 is an answer: a missing branch or file. */
    private inline fun <reified T> find(path: String): T? = http.getJsonOpt<T>(url(path), authed = true)

    private fun send(
        verb: Verb,
        path: String,
        body: JsonElement,
    ): String = http.sendJson(verb, url(path), body)

    override fun repository(repo: String): Repo? {
        val found = find<RepoJson>("/repos/$repo") ?: return null
        val defaultBranch = found.defaultBranch ?: throw KetchError.Parse(repo, "no default branch")
        return Repo(defaultBranch, found.permissions?.push ?: false)
    }

    override fun fork(repo: String): String {
        val fork = decodeApiJson<RepoJson>(repo, send(Verb.Post, "/repos/$repo/forks", JsonObject(emptyMap())))
        val fullName = fork.fullName ?: throw KetchError.Parse(repo, "fork has no name")
        val defaultBranch = fork.defaultBranch ?: "main"
        // A new fork is created asynchronously; its branches appear a few seconds after the API has already answered.
        repeat(FORK_TRIES) {
            if (branch(fullName, defaultBranch) != null) return fullName
            sleep(FORK_WAIT_S)
        }
        throw KetchError.Msg("fork $fullName is still being created; try again in a minute")
    }

    override fun syncFork(
        fork: String,
        branch: String,
    ) {
        send(Verb.Post, "/repos/$fork/merge-upstream", buildJsonObject { put("branch", branch) })
    }

    override fun branch(
        repo: String,
        branch: String,
    ): String? = find<RefJson>("/repos/$repo/git/ref/heads/${refPath(branch)}")?.target?.sha

    override fun createBranch(
        repo: String,
        branch: String,
        sha: String,
    ) {
        send(
            Verb.Post,
            "/repos/$repo/git/refs",
            buildJsonObject {
                put("ref", "refs/heads/$branch")
                put("sha", sha)
            },
        )
    }

    override fun resetBranch(
        repo: String,
        branch: String,
        sha: String,
    ) {
        send(
            Verb.Patch,
            "/repos/$repo/git/refs/heads/${refPath(branch)}",
            buildJsonObject {
                put("sha", sha)
                put("force", true)
            },
        )
    }

    override fun file(
        repo: String,
        branch: String,
        path: String,
    ): RemoteFile? {
        // A directory answers with a list; its last entry is what octocrab's `items.pop()` read.
        val item =
            find<JsonElement>("/repos/$repo/contents/${refPath(path)}?ref=${urlencode(branch)}")
                ?.let { if (it is JsonArray) it.lastOrNull() else it }
                ?: return null
        val content = decodeApiJson(path, item.toString(), ContentJson.serializer())
        return fileText(path, decodedContent(content.content), content.sha)
    }

    override fun writeFile(
        repo: String,
        branch: String,
        path: String,
        message: String,
        text: String,
        replacing: String?,
    ) {
        val body =
            buildJsonObject {
                put("message", message)
                put("content", Base64.encode(text.encodeToByteArray()))
                put("branch", branch)
                replacing?.let { put("sha", it) }
            }
        send(Verb.Put, "/repos/$repo/contents/${refPath(path)}", body)
    }

    override fun openPull(
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): String? {
        val request =
            buildJsonObject {
                put("title", title)
                put("head", head)
                put("base", base)
                put("body", body)
            }
        val created =
            try {
                send(Verb.Post, "/repos/$repo/pulls", request)
            } catch (e: KetchError.Http) {
                // 422 is how GitHub says the branch already has an open pull request.
                if (e.status == UNPROCESSABLE) return null
                throw e
            }
        return decodeApiJson<PullJson>(repo, created).htmlUrl.orEmpty()
    }

    override fun findPull(
        repo: String,
        head: String,
        base: String,
    ): String? =
        http
            .getJson<List<PullJson>>(url("/repos/$repo/pulls?state=open&head=${urlencode(head)}"), authed = true)
            // One head can carry open pull requests into several bases; only the one aimed at ours is this proposal's.
            .find { it.base?.ref == base }
            ?.htmlUrl

    companion object {
        /** The client for `cx`'s token; opening a pull request is done as the user, so there is no anonymous form. */
        fun create(cx: Ctx): GitHub {
            val token =
                cx.cfg.githubToken ?: throw KetchError.Msg(
                    "opening a registry pull request (`ketch registry push`) is done as you, which needs a GitHub token: " +
                        "set KETCH_GITHUB_TOKEN, or `github_token` in config.toml",
                )
            return connect(token, cx.report)
        }

        internal fun connect(
            token: String,
            report: Report,
        ) = GitHub(Http(token, report))
    }
}

/** A branch name or a file path in a URL path: each segment encoded, the slashes kept as the API expects them. */
private fun refPath(path: String) = path.split('/').joinToString("/") { urlencodePathSegment(it) }
