@file:OptIn(ExperimentalSerializationApi::class)

package ketch.core.model

import ketch.core.text.asciiLowercase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

@Serializable
@JsonIgnoreUnknownKeys
data class Checksum(
    /** Lowercase algorithm name, currently always `sha256`. */
    val algo: String,
    val hex: String,
) {
    companion object {
        fun sha256(hex: String) = Checksum("sha256", hex.asciiLowercase())
    }
}

@Serializable
@JsonIgnoreUnknownKeys
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    /** Checksum published by the source itself, when it offers one. */
    val digest: Checksum? = null,
    /** Extra headers a source (usually a plugin) needs for the download. */
    @Serializable(with = SortedStringMapSerializer::class) val headers: Map<String, String> = emptyMap(),
)

@Serializable
@JsonIgnoreUnknownKeys
data class Release(
    val version: Version,
    val tag: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val notes: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** Repository-level metadata, used by `info` and `search`. */
@Serializable
@JsonIgnoreUnknownKeys
data class SourceInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val homepage: String? = null,
    val stars: Long? = null,
    val license: String? = null,
    val archived: Boolean = false,
)

/**
 * Which stream of releases an installed package follows. `upgrade`, `outdated` and `list` look for its next
 * release in the same stream, so a package someone opted into prereleases for keeps getting them without `--pre`
 * on every run.
 */
@Serializable
enum class Channel(
    /** The lowercase name state and `--json` output use. */
    val asStr: String,
) {
    /** Published, non-prerelease releases; the default, so state written before channels existed reads as stable. */
    @SerialName("stable")
    Stable("stable"),

    /** Prereleases as well as stable releases. */
    @SerialName("pre")
    Pre("pre"),

    /** Drafts as well as prereleases and stable releases. Only GitHub has drafts, seen with a push-access token. */
    @SerialName("draft")
    Draft("draft"),

    /** One floating tag that is not a version (`nightly`, `dev`), republished; a new build under it is the upgrade. */
    @SerialName("dev")
    Dev("dev"),
    ;

    companion object {
        /**
         * The channel an install lands on: `draft` when the person opted into drafts, `dev` when the tag is not a
         * version, `pre` when they opted into prereleases or picked one by tag, `stable` otherwise. Opting in
         * counts even when the newest release happened to be stable, or the next upgrade would quietly drop them.
         */
        fun of(
            release: Release,
            includePrerelease: Boolean,
            includeDraft: Boolean,
        ): Channel =
            when {
                includeDraft || release.draft -> Draft

                // No version to compare against a later one: the tag itself is what the package follows.
                !release.version.isSemver -> Dev

                includePrerelease || release.prerelease || release.version.isPrerelease -> Pre

                else -> Stable
            }
    }
}
