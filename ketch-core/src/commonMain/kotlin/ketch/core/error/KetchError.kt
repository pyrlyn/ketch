package ketch.core.error

import kotlinx.io.files.Path

/**
 * The error every module throws (Rust `Error`). One hierarchy keeps the plumbing honest: any module may
 * construct any case, and the front end renders them uniformly. Cases carry the data needed to write a
 * message a user can act on — never a bare string where a path or URL exists.
 */
sealed class KetchError(
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Msg(
        text: String,
        cause: Throwable? = null,
    ) : KetchError(text, cause)

    class Io(
        val path: Path,
        val source: Throwable,
    ) : KetchError("$path: ${source.message}", source)

    class PlainIo(
        val source: Throwable,
    ) : KetchError(source.message ?: source.toString(), source)

    class Http(
        val url: String,
        val status: Int,
        val detail: String?,
    ) : KetchError("HTTP $status from $url")

    class Network(
        val url: String,
        val source: Throwable,
    ) : KetchError("network error requesting $url", source)

    class Parse(
        val what: String,
        val detail: String,
        cause: Throwable? = null,
    ) : KetchError("could not parse $what: $detail", cause)

    class UnknownScheme(
        val scheme: String,
    ) : KetchError("no source is registered for scheme `$scheme`")

    class NotInstalled(
        val name: String,
    ) : KetchError("`$name` is not installed")

    class NoRetained(
        val name: String,
    ) : KetchError("`$name` has no retained version to roll back to")

    class AlreadyInstalled(
        val name: String,
        val version: String,
    ) : KetchError("`$name` $version is already installed")

    /** `ketch install` of a package already installed without a version: the resolved release is the installed one. */
    class NoUpdate(
        val name: String,
        val version: String,
    ) : KetchError("cannot install `$name`: $version is already installed and no update is available")

    /**
     * `ketch install` of an installed package that has a newer release: the command asks before it updates.
     * [tag] is the release to update to, so the update installs exactly what the question named.
     */
    class UpdateAvailable(
        val name: String,
        val installed: String,
        val latest: String,
        val tag: String,
    ) : KetchError("`$name` $installed is installed and $latest is available")

    class Pinned(
        val name: String,
        val version: String,
    ) : KetchError("`$name` is pinned to $version")

    class NoRelease(
        val name: String,
    ) : KetchError("no release found for `$name`")

    class NoCompatibleAsset(
        val id: String,
        val tag: String,
        val target: String,
    ) : KetchError("release `$tag` of `$id` has no asset for $target")

    class ChecksumMismatch(
        val name: String,
        val expected: String,
        val actual: String,
    ) : KetchError("checksum mismatch for $name")

    class ChecksumMissing(
        val name: String,
    ) : KetchError("no published checksum for $name")

    class ChecksumDropped(
        val name: String,
        val asset: String,
    ) : KetchError("$name was installed against a published checksum, but $asset publishes none")

    class EmptyPayload(
        val path: Path,
    ) : KetchError("no installable files found in $path")

    class UnsupportedArchive(
        val path: Path,
    ) : KetchError("unsupported archive format: $path")

    class Command(
        val cmd: String,
        val status: String,
        val stderr: String,
        cause: Throwable? = null,
    ) : KetchError("`$cmd` failed ($status)", cause)

    class Plugin(
        val name: String,
        val detail: String,
        val stderr: String,
        cause: Throwable? = null,
    ) : KetchError("plugin `$name`: $detail", cause)

    class Config(
        text: String,
    ) : KetchError(text)

    /** The command has already told the user everything, line by line, and only the exit code is left. */
    class Reported(
        val code: Int,
    ) : KetchError("")

    /** Another operation holds the install tree's lock: a ketch process named by [pid], or just [lock] when unreadable. */
    class Busy(
        val pid: Long?,
        val lock: Path,
    ) : KetchError("another ketch process holds the lock (${if (pid != null) "pid $pid" else lock.toString()})")

    /** The caller's cancel token fired; nothing was left half-installed. */
    class Cancelled : KetchError("cancelled")

    /** Extra lines shown under the headline message, so it stays short while server bodies, diffs and stderr still show. */
    fun details(): List<String> =
        when (this) {
            is Http -> listOfNotNull(detail)
            is Command -> stderrLines(stderr)
            is Plugin -> stderrLines(stderr)
            is ChecksumMismatch -> listOf("expected $expected", "actual   $actual")
            else -> emptyList()
        }

    /** A short, actionable next step, when one exists. */
    fun hint(): String? =
        when (this) {
            is Http -> {
                when (status) {
                    HTTP_FORBIDDEN, HTTP_TOO_MANY -> "GitHub rate limit. Set GITHUB_TOKEN (or `gh auth token`) to raise it."
                    HTTP_NOT_FOUND -> "Check the owner/repo spelling, or the repo may be private."
                    else -> null
                }
            }

            is NoCompatibleAsset -> {
                "Run `ketch info <pkg>` to list assets, then pin one with `asset.include` in a manifest."
            }

            is ChecksumMismatch -> {
                "Refusing to install. Re-run to retry the download."
            }

            is ChecksumDropped -> {
                "A release that stops publishing checksums may have been replaced. " +
                    "Pass --allow-unverified (or --yes) to install it anyway."
            }

            is AlreadyInstalled, is NoUpdate -> {
                "Use --force to reinstall."
            }

            is UpdateAvailable -> {
                "Pass --yes to update it, or run `ketch upgrade $name`."
            }

            is Pinned -> {
                "Run `ketch unpin <pkg>` first."
            }

            is NoRetained -> {
                "Upgrade keeps the previous prefix until `ketch prune`."
            }

            is UnknownScheme -> {
                "Install a source plugin named `ketch-source-$scheme` on PATH or in the plugins dir."
            }

            else -> {
                null
            }
        }

    /** Process exit code. Distinct codes let scripts branch on failure class. */
    fun exitCode(): Int =
        when (this) {
            is NotInstalled, is NoRelease, is NoRetained -> EXIT_NOT_FOUND

            is AlreadyInstalled, is NoUpdate, is UpdateAvailable, is Pinned -> EXIT_REFUSED

            is ChecksumMismatch, is ChecksumMissing, is ChecksumDropped -> EXIT_UNVERIFIED

            is Http, is Network -> EXIT_NETWORK

            is Busy -> EXIT_BUSY

            is Reported -> code

            // The shell convention for "interrupted".
            is Cancelled -> EXIT_INTERRUPTED

            else -> 1
        }

    companion object {
        const val EXIT_NOT_FOUND = 4
        const val EXIT_REFUSED = 5
        const val EXIT_UNVERIFIED = 6
        const val EXIT_NETWORK = 7
        const val EXIT_BUSY = 8
        const val EXIT_INTERRUPTED = 130
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY = 429

        private fun stderrLines(stderr: String): List<String> = if (stderr.isBlank()) emptyList() else stderr.trim().lines()
    }
}
