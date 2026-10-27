package ketch.core.source

import ketch.core.error.KetchError
import ketch.core.http.Http
import ketch.core.model.PackageRef
import ketch.core.report.Ctx
import ketch.core.text.eqIgnoreAsciiCase

/** Every source available this run, resolved by scheme. */
class SourceRegistry private constructor(
    val all: List<Source>,
) {
    fun get(scheme: String): Source = all.firstOrNull { it.scheme().eqIgnoreAsciiCase(scheme) } ?: throw KetchError.UnknownScheme(scheme)

    fun forRef(reference: PackageRef) = get(reference.scheme)

    fun schemes() = all.map { it.scheme() }

    companion object {
        /**
         * Built-in sources plus every discovered plugin. Plugin discovery failures are reported as warnings rather than
         * aborting the command: a broken third-party plugin must not make `ketch install owner/repo` fail.
         */
        fun load(cx: Ctx): SourceRegistry {
            val plugins =
                discover(cx).mapNotNull { found ->
                    found
                        .onSuccess { cx.report.debug("plugin `${it.name}` provides `${it.scheme()}`") }
                        .onFailure { cx.report.warn("ignoring plugin: ${it.message}") }
                        .getOrNull()
                }
            return SourceRegistry(builtins(cx) + plugins)
        }

        /**
         * Only the built-in sources. Used by self-update, which must not depend on third-party plugins. Local stays
         * available even here: it cannot serve the host package, and omitting it would make `local:` fail only in that
         * one code path for no good reason.
         */
        fun builtinOnly(cx: Ctx) = SourceRegistry(builtins(cx))

        private fun builtins(cx: Ctx) = listOf(GitHubSource(Http(cx)), LocalSource())
    }
}
