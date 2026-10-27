package ketch.core.importer

import ketch.core.config.env
import ketch.core.error.KetchError
import ketch.core.http.Http
import ketch.core.report.Ctx
import ketch.core.text.rustTrim

/*
 * Where `ketch import` reads the other catalogues from, behind one interface. Every converter is written against
 * [Fetch], so a test hands it recorded responses and never reaches the network. [Endpoints] holds each catalogue's
 * base URL; the environment can point any of them elsewhere — a mirror, or a test's local server.
 */

/** A GET that answers with the body, or null when the resource is absent. */
fun interface Fetch {
    fun text(url: String): String?
}

/** Base URLs of the catalogues, with no trailing slash. */
data class Endpoints(
    /** Homebrew's JSON API: `<brew>/formula/<name>.json`. */
    val brew: String = "https://formulae.brew.sh/api",
    /** The GitHub contents API over `winget-pkgs/manifests`. */
    val wingetApi: String = "https://api.github.com/repos/microsoft/winget-pkgs/contents/manifests",
    /** Raw files of `winget-pkgs/manifests`. */
    val wingetRaw: String = "https://raw.githubusercontent.com/microsoft/winget-pkgs/master/manifests",
    /** archlinux.org, for the official repositories' JSON search. */
    val arch: String = "https://archlinux.org",
    /** Arch's packaging GitLab group, for an official package's `.SRCINFO`. */
    val archGitlab: String = "https://gitlab.archlinux.org/archlinux/packaging/packages",
    /** The AUR: its RPC and its cgit. */
    val aur: String = "https://aur.archlinux.org",
) {
    companion object {
        /** The defaults, each overridden by its `KETCH_IMPORT_*` variable. */
        fun fromEnv() = fromLookup(::env)

        internal fun fromLookup(get: (String) -> String?): Endpoints {
            val d = Endpoints()

            fun pick(
                key: String,
                default: String,
            ) = get(key)?.rustTrim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: default
            return Endpoints(
                brew = pick("KETCH_IMPORT_BREW", d.brew),
                wingetApi = pick("KETCH_IMPORT_WINGET_API", d.wingetApi),
                wingetRaw = pick("KETCH_IMPORT_WINGET_RAW", d.wingetRaw),
                arch = pick("KETCH_IMPORT_ARCH", d.arch),
                archGitlab = pick("KETCH_IMPORT_ARCH_GITLAB", d.archGitlab),
                aur = pick("KETCH_IMPORT_AUR", d.aur),
            )
        }
    }
}

private const val NOT_FOUND = 404

/**
 * The real thing: anonymous everywhere except the GitHub contents API, which gets the configured token because its
 * anonymous rate limit is sixty requests an hour.
 */
class HttpFetch(
    cx: Ctx,
    endpoints: Endpoints,
) : Fetch {
    private val anonymous = Http(null, cx.report)
    private val github = Http(cx)
    private val githubApi = endpoints.wingetApi

    override fun text(url: String): String? {
        val authed = url.startsWith(githubApi)
        return try {
            (if (authed) github else anonymous).getText(url, authed)
        } catch (e: KetchError.Http) {
            if (e.status == NOT_FOUND) null else throw e
        }
    }
}
