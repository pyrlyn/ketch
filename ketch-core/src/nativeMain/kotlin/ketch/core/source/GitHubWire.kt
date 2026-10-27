// What the GitHub REST API answers with, and how it becomes ketch's model.
package ketch.core.source

import ketch.core.model.Checksum
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.SourceInfo
import ketch.core.model.Version
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
internal data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    /** The API URL of the asset: the only way to download one of a draft. */
    val url: String? = null,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    /** Present on newer releases as `sha256:<hex>`. */
    val digest: String? = null,
)

@Serializable
internal data class GhRepo(
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    val homepage: String? = null,
    @SerialName("stargazers_count") val stargazersCount: Long? = null,
    val archived: Boolean = false,
    val license: GhLicense? = null,
    /** Present only on an authenticated request: what the token may do. */
    val permissions: GhPermissions? = null,
)

@Serializable
internal data class GhPermissions(
    val push: Boolean = false,
)

@Serializable
internal data class GhLicense(
    @SerialName("spdx_id") val spdxId: String? = null,
)

@Serializable
internal data class GhSearch(
    val items: List<GhRepo> = emptyList(),
)

// The public URL rather than the API one: the API redirects to a different host, and while the client drops the
// Authorization header on that hop, this URL is also what a browser and `curl -L` use, so a private repository needs
// the token on the request itself.
internal fun GhAsset.toAsset() = ReleaseAsset(name, browserDownloadUrl, size, contentType, digest?.let(::parseDigest)?.let(Checksum::sha256))

/** A listed release as ketch models it, [api] being the API base it came from. */
internal fun releaseFrom(
    release: GhRelease,
    api: String,
): Release =
    // The tag is the authority; `name` is often decorative ("July build").
    Release(
        version = Version.parse(release.tagName),
        tag = release.tagName,
        prerelease = release.prerelease,
        draft = release.draft,
        publishedAt = release.publishedAt,
        notes = release.body ?: release.name,
        assets = release.assets.mapNotNull { if (release.draft) draftAsset(it, api) else it.toAsset() },
    )

/**
 * A draft's asset, downloadable through the API. Its public URL answers 404 until the draft is published, so the
 * bytes come from the API asset URL with the token. That URL comes from the response, though, and the token goes
 * wherever it points: an asset whose URL is not on the API host is dropped, not fetched.
 */
private fun draftAsset(
    asset: GhAsset,
    api: String,
): ReleaseAsset? {
    val url = asset.url?.takeIf { it.startsWith("$api/") } ?: return null
    return asset.toAsset().copy(url = url)
}

internal fun GhRepo.toInfo() =
    SourceInfo(
        id = fullName,
        name = fullName.substringAfterLast('/'),
        description = description,
        homepage = homepage?.takeIf { it.isNotBlank() },
        stars = stargazersCount,
        license = license?.spdxId,
        archived = archived,
    )

/** Drafts appear only when asked for; prereleases stay only when asked, or when they are all the repository has published. */
internal fun publishedReleases(
    raw: List<GhRelease>,
    opts: ListOpts,
    api: String,
): List<Release> {
    val releases =
        raw
            // A draft without a tag name has nothing to be asked for by, or recorded under.
            .filter { !it.draft || (opts.includeDraft && it.tagName.isNotBlank()) }
            .map { releaseFrom(it, api) }
    // Prereleases are dropped only when there is something stable to drop them in favour of; plenty of projects have
    // never cut a stable tag, and `pick` handles that fallback if the list still holds them.
    return if (!opts.includePrerelease && releases.any { !it.prerelease }) releases.filter { !it.prerelease } else releases
}

/**
 * `1.2.3` as `v1.2.3`, when the request did not already carry a prefix. Only the one spelling is tried: a repository
 * tagging `release-1.2.3` is not guessable from the version, and the listing fallback still covers it.
 */
internal fun vPrefixed(tag: String): String? {
    val trimmed = tag.trim()
    return if (trimmed.isNotEmpty() && !trimmed.startsWith('v') && !trimmed.startsWith('V')) "v$trimmed" else null
}

private fun isUnreserved(b: Int) = b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code || b.toChar() in "-_.~"

@OptIn(ExperimentalStdlibApi::class)
private fun percentEncode(
    raw: String,
    spaceAsPlus: Boolean,
) = buildString {
    for (byte in raw.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        when {
            isUnreserved(b) -> append(b.toChar())
            spaceAsPlus && b == ' '.code -> append('+')
            else -> append('%').append(byte.toHexString(HexFormat.UpperCase))
        }
    }
}

/** Percent-encode a search query. Only the handful of characters that actually appear in package searches need escaping. */
internal fun urlencode(raw: String) = percentEncode(raw, spaceAsPlus = true)

/**
 * Percent-encode a value placed in one URL path segment. Unlike a query, spaces are `%20` and slashes must not become
 * path separators: release tags are allowed to contain both.
 */
internal fun urlencodePathSegment(raw: String) = percentEncode(raw, spaceAsPlus = false)
