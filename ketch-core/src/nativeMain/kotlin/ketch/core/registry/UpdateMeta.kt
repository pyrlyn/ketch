package ketch.core.registry

import fs.FsException
import fs.atomic
import fs.readText
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.error.fsKit
import ketch.core.error.fsKitAt
import ketch.core.model.nowUnix
import ketch.core.tomlfile.parse
import ketch.core.tomlfile.render
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `ketch update` recorded about the local registry copy. Lives under the ketch root as `registry.meta.toml`, not
 * inside the swapped package tree, so a fetch record cannot be mistaken for a package.
 */
@Serializable
data class UpdateMeta(
    /** `owner/repo` the tarball was fetched from. */
    val repo: String,
    /** Commit SHA from the GitHub tarball wrapper directory, when present. */
    val revision: String? = null,
    /** HTTP ETag from the fetch, when the transport exposed one. */
    val etag: String? = null,
    /** Unix seconds when the local copy was swapped in. */
    @SerialName("fetched_at") val fetchedAt: Long,
)

private const val MINUTE = 60L
private const val HOUR = 60L
private const val DAY = 24L

/** How old a fetch is, for `registry status` and doctor. No network. */
fun agePhrase(fetchedAt: Long): String {
    val now = nowUnix()
    val mins = (now - fetchedAt) / MINUTE
    return when {
        now < fetchedAt || mins < 1 -> "just now"
        mins < HOUR -> "${mins}m ago"
        mins / HOUR < DAY -> "${mins / HOUR}h ago"
        else -> "${mins / HOUR / DAY}d ago"
    }
}

/** The last successful `ketch update`, if one was recorded. */
fun loadMeta(cfg: Config): UpdateMeta? {
    val text =
        try {
            readText(cfg.registryMeta)
        } catch (e: FsException) {
            if (e.kind == FsException.Kind.NotFound) return null
            throw KetchError.fsKit(cfg.registryMeta, e)
        }
    return parse<UpdateMeta>(text, cfg.registryMeta.toString())
}

internal fun writeMeta(
    cfg: Config,
    meta: UpdateMeta,
) {
    val body = render(meta, cfg.registryMeta.toString())
    fsKitAt(cfg.registryMeta) { atomic(cfg.registryMeta, "# Written by `ketch update`. Do not edit.\n$body".encodeToByteArray()) }
}

private const val SHA_HEX = 40

/** Commit SHA GitHub puts on the tarball wrapper, `owner-repo-<40 hex>`. */
internal fun gitRevision(tree: Path): String? {
    val name = tree.name
    if ('-' !in name) return null
    return name.substringAfterLast('-').takeIf { sha -> sha.length == SHA_HEX && sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }?.lowercase()
}
