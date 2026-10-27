// A package's releases, as `ketch releases` and `ketch info --releases` list them. Every release is untrusted client
// data — a tag, a date stamp — so nothing here is filtered for a terminal; the callers sanitise what they show.
package ketch.core.releases

import ketch.core.model.InstalledPackage
import ketch.core.model.Release
import ketch.core.model.Version

/** What a listed release is. The one place that names a kind, so a new channel is one more arm here and not a second opinion in a renderer. */
enum class ReleaseKind(
    /** The lowercase name the table and `--json` use. */
    val label: String,
) {
    Stable("stable"),

    /** The forge's prerelease flag, or a semver prerelease in the tag. */
    Pre("pre"),

    /** Not published: `Release.draft`. Only GitHub returns these, and only when drafts are asked for. */
    Draft("draft"),

    /** A tag that is not a version at all (`nightly`), republished in place. */
    Dev("dev"),
    ;

    companion object {
        /**
         * Draft outranks everything: it is not out yet. A tag that is not a version outranks the prerelease flag,
         * because projects that float a `nightly` tag nearly always flag it prerelease too, and what matters to a
         * person is that it moves.
         */
        fun of(release: Release) =
            when {
                release.draft -> Draft
                release.version.sem == null -> Dev
                release.prerelease || release.version.isPrerelease -> Pre
                else -> Stable
            }
    }
}

/** One line of a release listing. */
data class Entry(
    val tag: String,
    val version: Version,
    /** The source's publication stamp, as it gave it. */
    val publishedAt: String?,
    val kind: ReleaseKind,
    /** Whether this is the release the package is installed from. */
    val installed: Boolean,
) {
    /**
     * The calendar day of the publication stamp, `YYYY-MM-DD`. A stamp that does not start with one is not shown: it
     * comes from a source plugin and is only ever as good as what that plugin wrote.
     */
    fun date(): String? {
        val day = publishedAt?.takeIf { it.length >= DAY.length }?.substring(0, DAY.length) ?: return null
        return day.takeIf { day.indices.all { i -> if (DAY[i] == '-') day[i] == '-' else day[i] in '0'..'9' } }
    }

    companion object {
        private const val DAY = "0000-00-00"

        internal fun of(
            release: Release,
            installed: InstalledPackage?,
        ) = Entry(release.tag, release.version, release.publishedAt, ReleaseKind.of(release), installed?.tag == release.tag)
    }
}
